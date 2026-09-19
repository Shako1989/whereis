package az.technest.whereis.plan.rtdn;

/**
 * What the handler decided about one notification. Persisted on {@code play_notifications.outcome}
 * and pinned by {@code ck_play_notifications_outcome} ({@code PlayNotificationEnumsTest} parses
 * V11).
 *
 * <p>V10's ledger could say a message SUCCEEDED ({@code processed_at}) or FAILED
 * ({@code processing_error}) and nothing else, which leaves the four successful dispositions
 * indistinguishable — "we applied Google's ACTIVE", "we discarded a stale ACTIVE", "we have never
 * seen this token" and "that was a test ping" all looked identical, and they are the four things
 * somebody debugging a wrong entitlement needs to tell apart.
 *
 * <p>{@link #setsProcessedAt()} is the invariant {@code ck_play_notifications_outcome_matches_processed}
 * enforces in the database: a message that applied nothing must not advance anybody's watermark,
 * because the fallback watermark for a token with no local row is
 * {@code max(event_time_millis) WHERE purchase_token = ? AND processed_at IS NOT NULL}.
 */
public enum PlayNotificationOutcome {

    /** The row was written: state refreshed from Google, or an entitlement revoked. */
    APPLIED,

    /** Older than the row's high-water mark, or another writer's answer is newer. Nothing written. */
    DISCARDED_STALE,

    /** A token this server has never seen and cannot attribute to an account. Nothing written. */
    NO_LOCAL_ROW,

    /** Deliberately not acted on: another app's package, a test ping, a one-time product. */
    IGNORED,

    /** Undecodable. Recorded once with {@code attempts} at the ceiling, never retried. */
    MALFORMED,

    /** Retried to the ceiling and still failing. The reconciler is the repair, not another delivery. */
    FAILED;

    /**
     * Whether this outcome means SUCCEEDED, i.e. sets {@code processed_at}. MALFORMED and FAILED
     * deliberately do not: they applied nothing, so they must not move a watermark.
     */
    public boolean setsProcessedAt() {
        return this != MALFORMED && this != FAILED;
    }
}
