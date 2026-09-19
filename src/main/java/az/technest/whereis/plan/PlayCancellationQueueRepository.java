package az.technest.whereis.plan;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The cancellation outbox. Not an owned aggregate — the owner is deleted by the time anything reads
 * it — so the scoped-finder rule does not apply.
 */
public interface PlayCancellationQueueRepository extends JpaRepository<PlayCancellationQueueEntry, UUID> {

    /**
     * ENQUEUE, IN ONE CONFLICT-TOLERANT STATEMENT.
     *
     * <p>Both halves matter, and both were review findings rather than taste:
     *
     * <p><strong>{@code INSERT … SELECT}</strong>, the same shape {@code storage_deletion_queue} is
     * filled with, so {@code AccountDeletionIT}'s constant-statement-count assertion still holds for
     * an account with any number of subscriptions.
     *
     * <p><strong>{@code ON CONFLICT (purchase_token) DO NOTHING}</strong>, because a unique
     * violation here would abort the whole deletion. This runs inside
     * {@code AccountDeletionService}'s single {@code @Transactional} method, so a
     * {@code DataIntegrityViolationException} rolls the entire cascade back and answers 409 on the
     * Play-mandated {@code DELETE /users/me} — nothing deleted, and the user unable to delete their
     * account until the janitor happens to drain. The collision is REACHABLE: account A enqueues
     * token T and is deleted (its {@code user_subscriptions} row cascades away, freeing T); the
     * janitor has not drained yet; the same person re-registers as B and the client's
     * {@code queryPurchasesAsync} re-posts T on the first foreground (cancel only turns auto-renew
     * off, so Play keeps reporting the purchase for the rest of the paid term); B deletes their
     * account. Cancelling once is sufficient — it is the same subscription — so a second enqueue is
     * correctly a no-op.
     *
     * <p><strong>The predicate is only {@code purchase_token IS NOT NULL}</strong>, and every
     * narrower version was rejected:
     * <ul>
     *   <li>a {@code state IN (…)} list decides from LOCAL state that may be stale — a row locally
     *       CANCELED or EXPIRED because an RTDN was lost, while Google is still billing;</li>
     *   <li>{@code superseded_by IS NULL} decides from an assumption that is not settled (whether a
     *       DEFERRED downgrade issues a new token), and would skip a row Google may still be
     *       renewing;</li>
     *   <li>{@code voided_at IS NULL} looks safe and is not: a refund of one payment WITHOUT
     *       revocation leaves auto-renew ON, so excluding voided rows would keep charging a card for
     *       a deleted account.</li>
     * </ul>
     * {@code purchases.subscriptions.cancel} is idempotent and an unknown token is a 404 the janitor
     * already deletes the row on, so over-enqueuing costs at most a handful of no-op API calls per
     * deleted account. Under-enqueuing costs the user a year of charges. That asymmetry decides it.
     *
     * <p>An OPERATOR grant has no token, which is what {@code purchase_token IS NOT NULL} excludes:
     * there is nothing at Google to cancel. {@code product_id IS NOT NULL} excludes the one other
     * shape V10 permits — a hand-written grant that was given a token but no product — because
     * {@code purchases.subscriptions.cancel} is the v1 endpoint and cannot be called without a
     * subscription id at all.
     *
     * @return how many rows were enqueued, for the deletion log line
     */
    @Modifying
    @Query(value = "insert into play_cancellation_queue (id, purchase_token, product_id, reason,"
            + " attempts, next_attempt_at, created_at)"
            + " select gen_random_uuid(), s.purchase_token, s.product_id, 'ACCOUNT_DELETED',"
            + "        0, now(), now()"
            + "   from user_subscriptions s"
            + "  where s.user_id = :userId"
            + "    and s.purchase_token is not null"
            + "    and s.product_id is not null"
            + " on conflict (purchase_token) do nothing", nativeQuery = true)
    int enqueueFor(@Param("userId") UUID userId);

    /** The due rows, oldest first, served by {@code ix_play_cancellation_queue_due}. */
    @Query("select e from PlayCancellationQueueEntry e"
            + " where e.nextAttemptAt < :now order by e.nextAttemptAt asc")
    List<PlayCancellationQueueEntry> findDue(@Param("now") Instant now,
                                             org.springframework.data.domain.Pageable page);
}
