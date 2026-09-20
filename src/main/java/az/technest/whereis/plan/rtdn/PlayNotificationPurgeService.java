package az.technest.whereis.plan.rtdn;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two ways a row leaves {@code play_notifications}, which until now had none at all: the RTDN
 * ledger kept every purchase token, order id, product id and raw Google payload it had ever been
 * sent, forever, including for accounts that had been deleted.
 *
 * <p><strong>Why two mechanisms and not one.</strong> They close different holes and neither
 * subsumes the other:
 * <ul>
 *   <li>{@link #purgeForUser} runs inside {@code AccountDeletionService}'s transaction and removes
 *       what can still be attributed to the account being deleted. It is the one that makes the
 *       public deletion page true, and it can only work BEFORE the {@code users} cascade, because
 *       the only linkage this table has is the purchase token and the join goes through
 *       {@code user_subscriptions};</li>
 *   <li>{@link #purgeReceivedBefore} is the age-based sweep behind {@code PlayNotificationJanitor}.
 *       It reaches the rows the first one cannot see at all — a notification for a token no account
 *       ever claimed, and a notification that arrives AFTER the deletion, when its token no longer
 *       resolves to anybody.</li>
 * </ul>
 *
 * <p>It lives in {@code plan/rtdn/} with the ledger, the entity and the repository, so everything
 * that writes this table is in one package; {@code user/} depends on this service rather than on
 * the repository, the same shape {@code SubscriptionCancellationService} has for
 * {@code play_cancellation_queue}.
 *
 * <p>No Play call in either method, so {@code AccountDeletionService} stays one
 * {@code @Transactional} method and
 * {@code OwnershipScopingArchTest#noTransactionalMethodCallsThePlayPort} stays green.
 */
@Service
@RequiredArgsConstructor
public class PlayNotificationPurgeService {

    private final PlayNotificationRepository notifications;

    /**
     * ACCOUNT DELETION. {@code REQUIRED}, deliberately, exactly like
     * {@code SubscriptionCancellationService#enqueueFor}: it is called from inside the deletion
     * transaction and must commit or roll back WITH it. Purging the ledger for an account that was
     * not deleted destroys a live subscriber's audit trail; deleting the account without purging
     * leaves Google's payloads behind the promise on the public page. Only sharing the transaction
     * rules out both.
     *
     * @param userId the JWT subject — never client input
     * @return how many ledger rows were removed, for the deletion log line
     */
    @Transactional
    public int purgeForUser(UUID userId) {
        return notifications.deleteAllLinkedToUser(userId);
    }

    /**
     * RETENTION. Its own transaction: the janitor holds none, so one statement is one transaction.
     *
     * @param cutoff rows RECEIVED before this instant go; see
     *               {@link PlayNotificationRepository#deleteAllReceivedBefore} for why neither the
     *               watermark nor idempotency can be broken by it
     * @return how many ledger rows were removed
     */
    @Transactional
    public int purgeReceivedBefore(Instant cutoff) {
        return notifications.deleteAllReceivedBefore(cutoff);
    }
}
