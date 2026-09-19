package az.technest.whereis.user;

import az.technest.whereis.assistant.AssistantMessageService;
import az.technest.whereis.item.ItemDeletionSummary;
import az.technest.whereis.item.ItemService;
import az.technest.whereis.location.LocationService;
import az.technest.whereis.plan.SubscriptionCancellationService;
import az.technest.whereis.space.SpaceService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Immediate, irreversible hard delete of the caller's own account and everything under it.
 *
 * <p>One transaction, a fixed order, no MinIO or AI call anywhere inside it:
 * <ol>
 *   <li>re-authenticate — nothing is locked or written before the password is accepted;</li>
 *   <li>take the per-space advisory locks every structural location writer also takes, so no
 *       concurrent re-parent, chain-create or space delete can interleave with the cascade;</li>
 *   <li><strong>enqueue the Play cancellations</strong> — FIRST of the writes, because stopping the
 *       money comes before dismantling the account, and because the {@code user_subscriptions} rows
 *       it reads cascade away with the {@code users} row at the end. Google Play does NOT cancel a
 *       subscription when a user deletes their app account, so without this step the person keeps
 *       being billed for a product they can no longer sign in to. It writes an OUTBOX row and makes
 *       no Play call, which is what keeps this a single {@code @Transactional} method and keeps
 *       {@code OwnershipScopingArchTest#noTransactionalMethodCallsThePlayPort} green;
 *       {@code PlayCancellationJanitor} drains it afterwards;</li>
 *   <li>assistant messages — the {@code users} cascade would remove them anyway, but going first
 *       means their ON DELETE SET NULL triggers on {@code item_id}/{@code space_id} never fire
 *       during the two bulk deletes below, and the summary count is exact;</li>
 *   <li>items — {@code items.current_location_id} is ON DELETE RESTRICT, and the outbox rows
 *       for the photos are enqueued inside that step before the {@code item_files} cascade
 *       erases the object keys;</li>
 *   <li>then the whole location forest in one statement (the self-referencing FK is NO ACTION,
 *       checked at end of statement);</li>
 *   <li>then the spaces (a space cannot go while it still has locations);</li>
 *   <li>then the user row — {@code refresh_tokens} cascade from it, which IS the session
 *       revocation. {@code RefreshTokenRevoker} is deliberately not called: it commits in its own
 *       REQUIRES_NEW transaction and would survive a rollback of this one.</li>
 * </ol>
 * MinIO binaries are removed afterwards by {@code StorageJanitor} from the outbox rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private final UserRepository userRepository;
    private final PasswordVerifier passwordVerifier;
    private final SpaceService spaceService;
    private final LocationService locationService;
    private final ItemService itemService;
    private final AssistantMessageService assistantMessageService;
    private final SubscriptionCancellationService subscriptionCancellations;

    /**
     * @param userId      the JWT subject — never client input
     * @param rawPassword the re-entered password; {@code null}/blank is rejected like a mismatch
     * @throws az.technest.whereis.common.error.ApiException 401 INVALID_CREDENTIALS when the password
     *                                                       is wrong or the account no longer exists
     */
    @Transactional
    public void deleteOwnAccount(UUID userId, String rawPassword) {
        // findById is legal here: the id is the authenticated subject, not a client-supplied
        // resource id, and a stale access token may outlive the row (double-submitted delete).
        User user = userRepository.findById(userId).orElse(null);
        passwordVerifier.requireMatch(user, rawPassword);

        List<UUID> spaceIds = spaceService.lockAllSpacesOfUser(userId);
        int cancellations = subscriptionCancellations.enqueueFor(userId);
        int assistantMessages = assistantMessageService.deleteAllForUser(userId);
        ItemDeletionSummary items = itemService.deleteAllForUser(userId);
        int locations = locationService.deleteAllForUser(userId);
        int spaces = spaceService.deleteAllForUser(userId);
        userRepository.delete(user);

        log.info("Account {} deleted: {} spaces (locked {}), {} locations, {} items, {} assistant messages, "
                        + "{} photo deletions enqueued, {} subscription cancellations enqueued",
                userId, spaces, spaceIds.size(), locations, items.items(), assistantMessages,
                items.filesEnqueued(), cancellations);
    }
}
