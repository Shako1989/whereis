package az.technest.whereis.plan.rtdn;

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
}
