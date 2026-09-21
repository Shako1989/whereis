package az.technest.whereis.marketplace;

import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.error.NotFoundException;
import az.technest.whereis.common.util.Names;
import az.technest.whereis.item.Item;
import az.technest.whereis.item.ItemService;
import az.technest.whereis.marketplace.seller.dto.ListingRequest;
import az.technest.whereis.marketplace.seller.dto.MyListingResponse;
import az.technest.whereis.plan.PlanLimitEnforcer;
import az.technest.whereis.storage.FileStorageService;
import az.technest.whereis.storage.ItemFile;
import az.technest.whereis.storage.ItemFileRepository;
import az.technest.whereis.storage.dto.ItemPrimaryImage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seller's side of the marketplace: publish, edit, end, and read back your own listings.
 * The anonymous board never reaches this class — it has its own package, its own DAO and its own
 * security chain.
 *
 * <p><strong>{@link #publish} is the only INSERT into {@code listings}</strong>, and the order of
 * its checks is itself a contract: ownership, then each specific conflict, then the plan cap LAST.
 * The codebase already settles this — {@code DUPLICATE_NAME} beats the space limit and
 * {@code LOCATION_NOT_FOUND} beats the item limit — because a user who is both at the cap and
 * missing a photo should be told about the photo, which is the thing they can fix in this session.
 */
@Service
@RequiredArgsConstructor
public class ListingService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ListingRepository listingRepository;
    private final BlockedSellerRepository blockedSellerRepository;
    private final ItemService itemService;
    private final ItemFileRepository itemFileRepository;
    private final FileStorageService fileStorageService;
    private final PlanLimitEnforcer planLimits;
    private final ListingWriter writer;

    /**
     * Publishes an item the caller owns. NOT {@code @Transactional} as a whole: {@link
     * FileStorageService#publishPhoto} reads and writes objects in MinIO, and no database
     * transaction may span those calls. The row is written afterwards, by {@link ListingWriter},
     * and a failure there leaves a published object the compensation inside {@code publishPhoto}
     * has already queued.
     */
    public MyListingResponse publish(UUID userId, UUID itemId, ListingRequest request) {
        // 1. Ownership first, always, and a miss is a 404 rather than a 403. Ahead of the block
        //    because it is this API's global contract and it leaks nothing new: any registered
        //    account can already probe item ids and be told 404.
        Item item = itemService.requireOwned(userId, itemId);

        // 2. THE SELLER-LEVEL SANCTION, ahead of every listing-level check. No amount of fixing
        //    this item gets past it, so telling them about a missing photo would be advice that
        //    cannot work — the same reason "you already listed this" beats "buy a bigger plan".
        requireNotBlocked(userId);

        // 3. An archived item is invisible in the owner's own list; it must not be visible to the
        //    whole internet.
        if (item.isArchived()) {
            throw ListingConflictException.itemArchived();
        }

        // 4. One live listing per item. Told before the plan cap, because "you already listed this"
        //    is the specific answer and "buy a bigger plan" would be a lie.
        if (listingRepository.existsByItemIdAndStatus(itemId, ListingStatus.ACTIVE)) {
            throw ListingConflictException.alreadyActive();
        }

        Draft draft = validate(request);
        UUID coverFileId = resolveCover(userId, itemId, request.coverFileId());

        // 5. The cap is the LAST check and the one immediately before the write.
        planLimits.requireRoomForAnotherListing(userId);

        // 6. Write the metadata-stripped public copy BEFORE the row exists, so a listing can never
        //    be visible with a cover the board cannot serve, and so a photo whose camera metadata
        //    cannot be removed refuses the publish instead of appearing with the GPS intact
        //    (409 LISTING_PHOTO_UNPUBLISHABLE — the one check that runs after the plan cap, because
        //    it is not a decision about the account and it needs the resolved cover). With
        //    minio.public-bucket unset this is a no-op and the listing goes up without an image.
        fileStorageService.publishPhoto(userId, itemId, coverFileId);

        // `false` by construction: step 2 just proved it, and re-reading the row here would be a
        // second answer to a question already settled inside this call.
        return MyListingResponse.of(writer.insert(userId, itemId, coverFileId, draft.title(),
                draft.description(), draft.price(), draft.phone(), draft.city(),
                draft.normalizedCity()), false);
    }

    /**
     * Re-runs the identical validation, because a publish-then-edit sequence would otherwise be a
     * way to put a listing on the board that publish itself would have refused.
     */
    @Transactional
    public MyListingResponse update(UUID userId, UUID listingId, ListingRequest request) {
        Listing listing = requireOwnedListing(userId, listingId);
        // The account-level judgement, before any judgement about this row: a blocked seller
        // editing their own SOLD listing should be told about the block, which is the fact that
        // changes what they can do next.
        requireNotBlocked(userId);
        if (listing.getStatus().isTerminal()) {
            throw ListingConflictException.notActive();
        }
        // A moderator's judgement stands. The route back is withdraw and publish a corrected
        // listing — a fresh row judged on its own merits, which also leaves the moderated one
        // intact as the evidence any report against it names.
        if (listing.isHidden()) {
            throw ListingConflictException.hidden();
        }
        Draft draft = validate(request);
        apply(listing, draft);
        return MyListingResponse.of(listing, false);
    }

    /** SOLD or WITHDRAWN. Both terminal; re-listing is a new row. */
    @Transactional
    public MyListingResponse end(UUID userId, UUID listingId, ListingStatus status) {
        if (!status.isTerminal()) {
            throw new BadRequestException(ErrorCode.VALIDATION_ERROR,
                    "A listing can only be ended as SOLD or WITHDRAWN");
        }
        Listing listing = requireOwnedListing(userId, listingId);
        // Deliberately allowed while hidden AND while the seller is blocked: a seller may always
        // take their own thing down, even when a moderator got there first. Taking that away would
        // make a sanction reach into the one control the person still legitimately has.
        if (listing.getStatus().isTerminal()) {
            throw ListingConflictException.notActive();
        }
        listing.setStatus(status);
        listing.setEndedAt(Instant.now());
        // The public object is DELETED now. Since V14 its URL is permanent and unsigned, so this
        // is the whole of the revocation — there is no expiring signature doing half the work. What
        // can outlive it is a copy a cache already took, bounded by the object's one-day
        // Cache-Control and by nothing else.
        fileStorageService.unpublishPhoto(listing.getItemId(), listing.getCoverFileId());
        return MyListingResponse.of(listing, isBlocked(userId));
    }

    @Transactional(readOnly = true)
    public MyListingResponse get(UUID userId, UUID listingId) {
        return MyListingResponse.of(requireOwnedListing(userId, listingId), isBlocked(userId));
    }

    /**
     * Every listing of the caller, with the account-level sanction read ONCE for the whole page
     * rather than once per row — "batch, never per-row", the same rule that governs location paths
     * and primary images.
     */
    @Transactional(readOnly = true)
    public Page<MyListingResponse> list(UUID userId, int page, int size, boolean activeOnly) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Listing> rows = activeOnly
                ? listingRepository.findAllByUserIdAndStatus(userId, ListingStatus.ACTIVE, pageable)
                : listingRepository.findAllByUserId(userId, pageable);
        boolean blocked = isBlocked(userId);
        return rows.map(listing -> MyListingResponse.of(listing, blocked));
    }

    /**
     * Account deletion: every listing of the user in ONE statement, with {@code listing_reports}
     * cascading from them.
     *
     * <p>{@code MANDATORY} like {@code ItemService#deleteAllForUser}: mass-unpublishing a person's
     * listings outside a deletion transaction is never a legitimate operation, and MANDATORY makes
     * a call from anywhere else fail immediately rather than commit.
     *
     * <p>The published photo COPIES are not enqueued here — {@code FileStorageService
     * .enqueueAllForUser} already does that for every file of every item of the user, and it runs
     * later in the same transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteAllForUser(UUID userId) {
        return listingRepository.deleteAllByUserId(userId);
    }

    // ------------------------------------------------------------------ internals

    /** The validated, cleaned form of a request — computed once, by both write paths. */
    private record Draft(String title, String description, BigDecimal price, String phone,
                         String city, String normalizedCity) {
    }

    private Draft validate(ListingRequest request) {
        String title = require(Names.clean(request.title()), "title");
        String description = require(Names.clean(request.description()), "description");

        int length = MarketplaceRules.lengthOf(description);
        if (length < MarketplaceRules.MIN_DESCRIPTION_LENGTH) {
            throw new ListingDescriptionTooShortException(length);
        }
        // Measured on the CLEANED form and in CODE POINTS: bean validation runs before cleaning, so
        // "a" plus two hundred spaces passes @Size and stores one character, and String.length()
        // counts 39 astral-plane emoji as 78 — the service would accept exactly what the CHECK then
        // refuses, and the user would get a 500 instead of a sentence.
        if (length > MarketplaceRules.MAX_DESCRIPTION_LENGTH) {
            throw new BadRequestException(ErrorCode.VALIDATION_ERROR,
                    "Description must be at most " + MarketplaceRules.MAX_DESCRIPTION_LENGTH
                            + " characters");
        }
        // An anonymous board with no accountable author is the perfect place to plant a link.
        if (MarketplaceRules.isLinkLike(description) || MarketplaceRules.isLinkLike(title)) {
            throw new BadRequestException(ErrorCode.VALIDATION_ERROR,
                    "Links are not allowed in a listing's title or description");
        }

        String city = require(Names.clean(request.city()), "city");
        if (MarketplaceRules.isLinkLike(city)) {
            throw new BadRequestException(ErrorCode.VALIDATION_ERROR, "City must not contain a link");
        }

        String phone = MarketplaceRules.normalizePhone(request.contactPhone());
        if (!MarketplaceRules.isPhone(phone)) {
            throw new BadRequestException(ErrorCode.VALIDATION_ERROR,
                    "Enter a contact phone number with 7 to 15 digits");
        }

        return new Draft(title, description, request.price(), phone, city, Names.normalize(city));
    }

    private static String require(String cleaned, String field) {
        if (cleaned == null || cleaned.isBlank()) {
            throw new BadRequestException(ErrorCode.VALIDATION_ERROR, "A listing needs a " + field);
        }
        return cleaned;
    }

    /**
     * The photo prerequisite. An explicit id is checked against THIS item's files — the same two
     * scoped lookups {@code FileStorageService.presign} performs — so a file id belonging to
     * another item is a 404 and never a published stranger's photograph. With no id, the
     * established primary-else-oldest cover is used.
     */
    private UUID resolveCover(UUID userId, UUID itemId, UUID requested) {
        if (requested != null) {
            ItemFile file = itemFileRepository.findByIdAndItemId(requested, itemId)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.FILE_NOT_FOUND, "File not found"));
            return file.getId();
        }
        Map<UUID, ItemPrimaryImage> covers = fileStorageService.primaryImages(java.util.List.of(itemId));
        ItemPrimaryImage cover = covers.get(itemId);
        if (cover == null) {
            throw ListingConflictException.photoRequired();
        }
        return cover.fileId();
    }

    private static void apply(Listing listing, Draft draft) {
        listing.setTitle(draft.title());
        listing.setDescription(draft.description());
        listing.setPriceAmount(draft.price());
        listing.setContactPhone(draft.phone());
        listing.setCity(draft.city());
        listing.setNormalizedCity(draft.normalizedCity());
    }

    private Listing requireOwnedListing(UUID userId, UUID listingId) {
        return listingRepository.findByIdAndUserId(listingId, userId)
                .orElseThrow(ListingNotFoundException::new);
    }

    /**
     * The publishing sanction, as a refusal that names its reason.
     *
     * <p><strong>This is the courtesy, not the invariant.</strong> What actually keeps a blocked
     * seller off the board is the {@code NOT EXISTS} clause in {@code MarketBoardDao}'s visibility
     * predicate, which is evaluated per request and therefore also catches a publish that was
     * already in flight when the block committed. This check exists so the seller gets a sentence
     * explaining what happened instead of a listing that silently nobody can see.
     */
    private void requireNotBlocked(UUID userId) {
        blockedSellerRepository.findByUserId(userId).ifPresent(block -> {
            throw MarketplaceBlockedException.of(block.getReason());
        });
    }

    private boolean isBlocked(UUID userId) {
        return blockedSellerRepository.findByUserId(userId).isPresent();
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
