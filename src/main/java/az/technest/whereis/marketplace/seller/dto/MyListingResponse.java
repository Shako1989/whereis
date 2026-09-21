package az.technest.whereis.marketplace.seller.dto;

import az.technest.whereis.marketplace.Listing;
import az.technest.whereis.marketplace.ListingCurrency;
import az.technest.whereis.marketplace.ListingHiddenReason;
import az.technest.whereis.marketplace.ListingStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The SELLER's view of their own listing — richer than the public one, and a different record.
 *
 * <p>It carries {@code itemId} and {@code hiddenReason}, which the public DTO must never have: the
 * seller needs to know which of their items this is, and a listing that vanished from the board
 * without explanation is how a user concludes the application is broken. The operator's free-text
 * {@code hidden_note} is deliberately absent — it is internal, untranslated, and written for a
 * colleague rather than for the person it is about.
 *
 * <p><strong>{@code sellerBlocked} and {@code hidden} are INDEPENDENT FACTS and neither is derived
 * from the other</strong>, the same relationship {@code plan} and {@code subscription} have on
 * {@code GET /users/me/plan}. {@code hidden} is a judgement about THIS row; {@code sellerBlocked} is
 * a judgement about the ACCOUNT, and a blocked seller's listings are off the board while their rows
 * stay {@code ACTIVE} with {@code hidden_at} NULL — because V13 hides them by filtering the board,
 * not by mutating them. Without this field the seller's own screen would show a healthy ACTIVE
 * listing that no visitor can see, which is the "vanished without explanation" failure again, one
 * level up. It is a per-request fact repeated on every row of the page rather than a page-level one
 * because there is no page envelope to hang it on: {@code GET /users/me/listings} returns a Spring
 * {@code Page}, whose shape is not ours to extend.
 */
public record MyListingResponse(
        UUID id,
        UUID itemId,
        ListingStatus status,
        boolean hidden,
        ListingHiddenReason hiddenReason,
        boolean sellerBlocked,
        String title,
        String description,
        BigDecimal price,
        ListingCurrency currency,
        String contactPhone,
        String city,
        UUID coverFileId,
        Instant createdAt,
        Instant endedAt) {

    /**
     * The only factory.
     *
     * @param sellerBlocked whether the OWNER of this listing is barred from the board — a fact
     *                      about the account that no field of {@code listing} carries or could
     *                      carry, which is why it is a parameter and not read off the entity
     */
    public static MyListingResponse of(Listing listing, boolean sellerBlocked) {
        return new MyListingResponse(
                listing.getId(),
                listing.getItemId(),
                listing.getStatus(),
                listing.isHidden(),
                listing.getHiddenReason(),
                sellerBlocked,
                listing.getTitle(),
                listing.getDescription(),
                listing.getPriceAmount(),
                listing.getPriceCurrency(),
                listing.getContactPhone(),
                listing.getCity(),
                listing.getCoverFileId(),
                listing.getCreatedAt(),
                listing.getEndedAt());
    }
}
