package az.technest.whereis.plan.rtdn;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The RTDN ledger. Not an owned aggregate — there is no {@code user_id} to scope by, because a
 * notification can arrive for a token this server has never seen — so the scoped-finder rule does
 * not apply here; {@code onlyThePlanPackageReadsSubscriptions} keeps it inside {@code plan/}
 * regardless.
 */
public interface PlayNotificationRepository extends JpaRepository<PlayNotification, String> {

    /**
     * THE WATERMARK for a token with no {@code user_subscriptions} row yet — V10's designed
     * fallback, served by {@code ix_play_notifications_token}.
     *
     * <p>{@code processed_at IS NOT NULL} is the load-bearing predicate: a MALFORMED or FAILED
     * message applied nothing, so it must not advance anybody's watermark. {@code max()} ignores
     * NULLs, so the MALFORMED rows V11 allows to omit {@code event_time_millis} cannot affect it
     * either way.
     */
    @Query("""
            select max(n.eventTimeMillis) from PlayNotification n
             where n.purchaseToken = :token and n.processedAt is not null
            """)
    Long watermarkOf(@Param("token") String token);

    /**
     * THE LEASE. One conditional statement that claims a pending ledger row for this delivery and
     * bumps {@code attempts} in the same breath.
     *
     * <p>A primary-key collision alone cannot tell "an earlier delivery crashed mid-flight" from
     * "another thread is holding this message right now" — both see a row with
     * {@code processed_at IS NULL}. Making the resume branch a conditional UPDATE settles it: zero
     * rows affected means somebody else owns the message (or it is already finished, or it is
     * already at the ceiling), and the handler answers 200 without touching anything.
     *
     * <p>{@code attempts} is bumped BEFORE the work, so a crash still counts and a poison message
     * still converges on its ceiling.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update play_notifications
               set attempts = attempts + 1, last_attempt_at = now()
             where message_id = :messageId
               and processed_at is null
               and attempts < :maxAttempts
            """, nativeQuery = true)
    int claim(@Param("messageId") String messageId, @Param("maxAttempts") int maxAttempts);

    /**
     * ACCOUNT DELETION, half one: every ledger row that can still be attributed to this account.
     *
     * <p><strong>The only linkage this table offers is the purchase token.</strong> V10 gave it no
     * {@code user_id} and no foreign key on purpose — a notification can arrive for a token this
     * server has never seen — so "the rows belonging to user X" is not a column but a join through
     * {@code user_subscriptions}, and it is only answerable while those rows still exist. That is
     * why this runs inside {@code AccountDeletionService}'s transaction and BEFORE the {@code users}
     * row goes: after the cascade there is nothing left to join against, and the purchase tokens,
     * order ids, product ids and Google's raw payloads would stay in this table indefinitely —
     * which is what the public deletion page promises they do not.
     *
     * <p>ONE statement whatever the subscription count, like {@code storage_deletion_queue}'s
     * {@code INSERT … SELECT}, so {@code AccountDeletionIT}'s constant-statement-count assertion
     * still holds. No {@code clearAutomatically}: the caller is still holding the managed
     * {@code User} it deletes at the end of the cascade.
     *
     * <p>What this deliberately does NOT reach is a notification that arrives AFTER the account is
     * gone — its token no longer resolves to anybody. {@link #deleteAllReceivedBefore} is the other
     * half, and the reason the two are not one mechanism.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            delete from PlayNotification n
             where n.purchaseToken in (select s.purchaseToken from UserSubscription s
                                        where s.userId = :userId and s.purchaseToken is not null)
            """)
    int deleteAllLinkedToUser(@Param("userId") UUID userId);

    /**
     * RETENTION, half two: the ledger as a whole, by age, so it cannot grow forever.
     *
     * <p>Gated on {@code received_at} rather than {@code event_time_millis}. {@code received_at} is
     * NOT NULL and is our own clock, so it is both always present (V11 lets a MALFORMED row omit
     * Google's event time) and the honest measure of how long we have held the data — which is what
     * a retention promise is about.
     *
     * <p><strong>It cannot break the fallback watermark.</strong> That watermark is
     * {@code max(event_time_millis)} over the rows for ONE token, so removing only rows older than
     * the window either leaves the max untouched or removes every row for that token — and in the
     * latter case every removed event is older than anything that can still arrive, because Pub/Sub
     * stops redelivering after its own 7-day message retention. A null watermark then accepts the
     * next notification, which is the same decision the preserved one would have made. Monotonicity
     * per token survives, and that is the property — not the row count.
     *
     * <p>It cannot break idempotency either, for the same 7-day reason: a redelivery only has to
     * collide with the primary key while Pub/Sub can still produce one, and the window is several
     * times longer than that.
     *
     * <p>Pending rows are included. A row older than the window that never reached an outcome can
     * no longer be redelivered, so it is dead weight in the work queue rather than work.
     *
     * <p>No index on {@code received_at} and no migration for one:
     * {@code ix_play_notifications_unprocessed} and {@code ix_play_notifications_token} are both
     * partial and serve neither this predicate nor a rewrite of it, and at this table's size (a
     * handful of notifications per live subscription per month, against a reconciler ceiling of
     * ~1,200 live subscriptions) a daily sequential scan is cheaper than the index would be worth.
     * The same ruling BR-9 made about a composite index on {@code items}.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from PlayNotification n where n.receivedAt < :cutoff")
    int deleteAllReceivedBefore(@Param("cutoff") Instant cutoff);
}
