package az.technest.whereis.plan.reconcile;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the three scheduled billing components. Bound from {@code whereis.play.reconcile.*},
 * {@code whereis.play.voided-sweep.*} and {@code whereis.play.cancellation.*} through one record so
 * the quota arithmetic below lives next to the numbers it is about.
 *
 * <p><strong>Each component has its own {@code enabled} flag</strong>, and that is the operator's
 * answer to "we are now running two containers": all three are plain {@code @Scheduled} methods
 * with no distributed lock, exactly like {@code StorageJanitor}. Correctness survives a second
 * instance — every write is idempotent and guarded by compare-and-set or write-once — but the Play
 * quota spend doubles, and the fix is to turn the sweeps off on all but one. Accepted limitation,
 * and the first thing to revisit if this ever scales horizontally.
 *
 * <p><strong>The reconciler's throughput CEILING, stated because it is a ceiling and not
 * headroom.</strong> A batch of 25 every 15 minutes is 2,400 {@code subscriptionsv2.get} calls a
 * day; a 12-hour staleness target needs roughly two refreshes per live row per day, so the sweep
 * keeps up to about <strong>1,200 live subscriptions</strong> and then silently stops — rows simply
 * never reach the head of the queue, with no error and nothing in a log. {@link SubscriptionReconciler}
 * therefore WARNs when a run returns a full batch AND the oldest candidate is older than twice the
 * staleness threshold, which is the observable signal that it has fallen behind. The unacknowledged
 * branch degrades the same way, and that branch is the one that costs money (Google auto-refunds
 * after 3 days).
 *
 * <p><strong>The voided sweep is paced against the TIGHTER of Google's two inconsistently
 * documented quotas.</strong> Google documents {@code voidedpurchases} as 6000/day + 30 per 30 s on
 * one page and 3000 QPM on another; 6000/day is tighter than 3000 QPM × 1440, and 30-per-30-s is
 * tighter than 3000/min, so: 6000/day and 30/30 s. With {@code maxPages} 20 and a 2 s minimum
 * interval that is ≤ 30 requests per 60 s — HALF the 30-per-30-s allowance — and 4 runs/day × 20
 * pages = ≤ 80 requests/day against 6000. Two orders of magnitude of headroom, deliberately,
 * because the cost of being wrong is a 429 that makes the refund backstop silently stop working.
 *
 * @param reconcile    drift repair and the acknowledgement retry
 * @param voidedSweep  the refund backstop
 * @param cancellation the account-deletion cancellation janitor
 */
@ConfigurationProperties("whereis.play")
public record ReconcileProperties(Reconcile reconcile, VoidedSweep voidedSweep, Cancellation cancellation) {

    public ReconcileProperties {
        reconcile = reconcile == null ? new Reconcile(null, null, null, null, null, null) : reconcile;
        voidedSweep = voidedSweep == null ? new VoidedSweep(null, null, null, null) : voidedSweep;
        cancellation = cancellation == null ? new Cancellation(null, null) : cancellation;
    }

    /**
     * @param enabled    default true
     * @param batchSize  rows per run; default 25
     * @param staleAfter how old {@code verified_at} may get before a row is a candidate; default 12h
     * @param graveyard  how far past expiry a row is still worth asking about; default 35 days,
     *                   because ON_HOLD lasts up to 30 and ends in {@code SUBSCRIPTION_RECOVERED}
     * @param ackWindow  how long an unacknowledged row stays on the fast path; default 7 days
     * @param minInterval between Google calls inside one run; default 200 ms
     */
    public record Reconcile(Boolean enabled, Integer batchSize, Duration staleAfter, Duration graveyard,
                            Duration ackWindow, Duration minInterval) {

        public Reconcile {
            enabled = enabled == null || enabled;
            batchSize = batchSize == null || batchSize <= 0 ? 25 : batchSize;
            staleAfter = staleAfter == null ? Duration.ofHours(12) : staleAfter;
            graveyard = graveyard == null ? Duration.ofDays(35) : graveyard;
            ackWindow = ackWindow == null ? Duration.ofDays(7) : ackWindow;
            minInterval = minInterval == null ? Duration.ofMillis(200) : minInterval;
        }
    }

    /**
     * @param enabled     default true
     * @param lookback    a FIXED look-back on every run, not a persisted watermark; default 7 days.
     *                    Google only serves voided purchases from roughly the last 30 days, runs
     *                    happen every 6 hours, and applying a void twice is a no-op because
     *                    {@code voided_at} is write-once — so a 28×-redundant window is free, needs
     *                    no new state, self-heals after any outage shorter than a week, and has no
     *                    watermark to corrupt. The second reason is just as important: a void can
     *                    arrive BEFORE the purchase is known to us, and a wide window re-applies it
     *                    once the client's foreground sync has created the row
     * @param maxPages    pages per run; default 20. Hitting it logs a WARN, because it means a week
     *                    produced more than 20,000 refunds — a business event long before it is an
     *                    engineering one
     * @param minInterval between page requests; default 2 s
     */
    public record VoidedSweep(Boolean enabled, Duration lookback, Integer maxPages, Duration minInterval) {

        public VoidedSweep {
            enabled = enabled == null || enabled;
            lookback = lookback == null ? Duration.ofDays(7) : lookback;
            maxPages = maxPages == null || maxPages <= 0 ? 20 : maxPages;
            minInterval = minInterval == null ? Duration.ofSeconds(2) : minInterval;
        }
    }

    /**
     * @param enabled   default true
     * @param batchSize due rows per sweep; default 20
     *
     * <p>There is deliberately NO {@code giveUpAfter} here. How long a purchase token may be kept
     * after the account is gone is stated on the public account-deletion page in both languages, so
     * the janitor reads it from {@code whereis.legal.cancellation-retry-days} — the SAME property
     * the page is rendered from, and {@code LegalPages} refuses to boot while it is unset. That is
     * the coupling {@code WHEREIS_LEGAL_BACKUP_RETENTION_DAYS} already has with the backup script,
     * and re-introducing the un-rendered literal form here is the mistake this project has already
     * made once.
     */
    public record Cancellation(Boolean enabled, Integer batchSize) {

        public Cancellation {
            enabled = enabled == null || enabled;
            batchSize = batchSize == null || batchSize <= 0 ? 20 : batchSize;
        }
    }
}
