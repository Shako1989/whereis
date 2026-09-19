package az.technest.whereis.plan.rtdn;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write to {@code play_notifications}, each in its own transaction, reached by
 * {@link RtdnService} through the Spring proxy — the same shape {@code SubscriptionWriter} has, and
 * for the same reason: the handler holds no transaction, so the Google round trip on the REFRESH
 * path sits strictly between transactions.
 *
 * <p>INSERT FIRST, PROCESS AFTER. {@code message_id} is the primary key and Pub/Sub guarantees
 * at-least-once delivery, so a redelivery is a primary-key COLLISION rather than an
 * application-level "have I seen this?" query whose answer can be stale.
 */
@Service
@RequiredArgsConstructor
public class PlayNotificationLedger {

    @PersistenceContext
    private EntityManager entityManager;

    private final PlayNotificationRepository notifications;

    /**
     * <strong>{@code persist}, never {@code save()}.</strong> {@code save()} on a detached entity
     * with an ASSIGNED id performs a MERGE — a SELECT followed by an UPDATE — which would silently
     * overwrite the first delivery's bookkeeping instead of colliding, and the whole dedup design
     * rests on the collision.
     *
     * <p>The row is written with {@code attempts = 1} already: bumping before the work means a
     * crash still counts and a poison message still converges on its ceiling.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException on a redelivery. <strong>Catch
     *         it OUTSIDE any transaction</strong> — a constraint violation marks a JPA transaction
     *         rollback-only and leaves the persistence context unusable, so re-reading in the same
     *         method cannot work.
     */
    @Transactional
    public void insertNew(PlayNotification notification) {
        entityManager.persist(notification);
        entityManager.flush();
    }

    /** @return the number of rows claimed: 1 = this delivery owns the message, 0 = somebody else does */
    @Transactional
    public int claim(String messageId, int maxAttempts) {
        return notifications.claim(messageId, maxAttempts);
    }

    @Transactional(readOnly = true)
    public Optional<PlayNotification> load(String messageId) {
        return notifications.findById(messageId);
    }

    /**
     * Record the handler's verdict. {@code processed_at} is set only for the four outcomes that
     * mean SUCCEEDED ({@link PlayNotificationOutcome#setsProcessedAt()}), which is what keeps V10's
     * watermark read correct: a message that applied nothing must not advance anybody's watermark.
     *
     * <p>{@code processing_error} is cleared on a successful outcome because
     * {@code ck_play_notifications_error_only_while_pending} forbids both at once — a row that
     * failed twice and then succeeded carries no error, which is the truth.
     */
    @Transactional
    public void finish(String messageId, PlayNotificationOutcome outcome, String error) {
        notifications.findById(messageId).ifPresent(row -> {
            row.setOutcome(outcome);
            if (outcome.setsProcessedAt()) {
                row.setProcessedAt(Instant.now());
                row.setProcessingError(null);
            } else {
                row.setProcessedAt(null);
                row.setProcessingError(truncate(error));
            }
        });
    }

    /** A failure that is still pending: no outcome yet, the error recorded, the row still in the queue. */
    @Transactional
    public void recordFailure(String messageId, String error) {
        notifications.findById(messageId).ifPresent(row -> {
            row.setOutcome(null);
            row.setProcessedAt(null);
            row.setProcessingError(truncate(error));
        });
    }

    /** THE WATERMARK for a token with no {@code user_subscriptions} row yet. */
    @Transactional(readOnly = true)
    public Long watermarkOf(String purchaseToken) {
        return notifications.watermarkOf(purchaseToken);
    }

    /** {@code processing_error} is varchar(500): an error string, never a stack trace, never a token. */
    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
