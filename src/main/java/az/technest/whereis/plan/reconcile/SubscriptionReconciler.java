package az.technest.whereis.plan.reconcile;

import az.technest.whereis.plan.PurchaseTokens;
import az.technest.whereis.plan.SubscriptionLinkResolver;
import az.technest.whereis.plan.SubscriptionSnapshots;
import az.technest.whereis.plan.SubscriptionWriter;
import az.technest.whereis.plan.UserSubscription;
import az.technest.whereis.plan.UserSubscriptionRepository;
import az.technest.whereis.plan.play.PlayApiException;
import az.technest.whereis.plan.play.PlayPurchaseInvalidException;
import az.technest.whereis.plan.play.PlaySubscription;
import az.technest.whereis.plan.play.PlaySubscriptionsApi;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drift repair and the acknowledgement retry: re-read the live subscriptions Google has not
 * confirmed recently and write what it says.
 *
 * <p>It exists because every notification can be lost — Pub/Sub retention runs out, a message is
 * given up on, a deploy restarts mid-delivery — and because wave 1 shipped with a hole that costs
 * real money: a single transient 5xx during {@code acknowledge} left an account this database says
 * is entitled for a year and Google has silently refunded. Google auto-refunds an unacknowledged
 * purchase after 3 days, and <strong>after 5 MINUTES for a test purchase</strong>, which is every
 * purchase on the closed track this was built for.
 *
 * <p><strong>No {@code @Transactional} anywhere on this class — not on a method and not on the
 * class.</strong> Every database touch goes through {@code SubscriptionWriter}, so the Google round
 * trip never holds a connection or a lock. The ArchUnit rule inspects each method's OWN
 * annotations, so a class-level {@code @Transactional} here would slip past it entirely;
 * {@code NoTransactionAroundThePlayPortTest} asserts the absence directly for exactly that reason.
 *
 * <p>How this and the RTDN handler avoid fighting is documented on {@code SubscriptionWriter}: one
 * mapping, compare-and-set on {@code verified_at} under a row lock, a watermark only a notification
 * may move, and write-once {@code voided_at} that {@code reconcile} refuses to run against.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionReconciler {

    private final UserSubscriptionRepository subscriptions;
    private final SubscriptionWriter writer;
    private final SubscriptionSnapshots snapshots;
    private final SubscriptionLinkResolver linkResolver;
    private final PlaySubscriptionsApi play;
    private final ReconcileProperties properties;

    @Scheduled(fixedDelayString = "${whereis.play.reconcile.delay:PT15M}",
            initialDelayString = "${whereis.play.reconcile.initial-delay:PT2M}")
    public void sweep() {
        if (!Boolean.TRUE.equals(properties.reconcile().enabled())) {
            return;
        }
        runOnce();
    }

    /**
     * ONE PASS, without consulting the {@code enabled} flag — see
     * {@link PlayCancellationJanitor#runOnce()} for why the integration suite needs it.
     */
    public void runOnce() {
        ReconcileProperties.Reconcile config = properties.reconcile();
        Instant now = Instant.now();
        List<UserSubscription> candidates = subscriptions.reconcileCandidates(
                now.minus(config.graveyard()),
                now.minus(config.ackWindow()),
                now.minus(config.staleAfter()),
                PageRequest.of(0, config.batchSize()));
        if (candidates.isEmpty()) {
            return;
        }
        warnIfFallingBehind(candidates, config, now);
        int repaired = 0;
        for (UserSubscription row : candidates) {
            if (reconcileOne(row)) {
                repaired++;
            }
            pause(config.minInterval());
        }
        log.info("Subscription reconcile: {} candidates, {} refreshed", candidates.size(), repaired);
    }

    /**
     * ONE ROW, AND NOTHING IT THROWS MAY STOP THE BATCH.
     *
     * <p>The outer {@code catch (RuntimeException)} is the load-bearing part. Without it, one row
     * whose Google answer maps to no known product — or any other unforeseen failure — propagates
     * out of the loop and aborts the whole batch; and because {@code verified_at} was never bumped,
     * that row stays the OLDEST candidate and is picked first on the next run too. The sweep would
     * starve permanently on one bad row, every fifteen minutes, forever, with no repair path,
     * because the reconciler IS the repair path.
     */
    private boolean reconcileOne(UserSubscription row) {
        String token = row.getPurchaseToken();
        Instant expectedVerifiedAt = row.getVerifiedAt();
        try {
            PlaySubscription google = play.get(token);
            Instant verifiedAt = Instant.now();
            SubscriptionWriter.Snapshot snapshot = snapshots.refreshOf(row, google, verifiedAt);
            boolean written = writer.reconcile(row.getId(), snapshot, expectedVerifiedAt);
            if (!written) {
                // Somebody else wrote a newer answer, or the row was voided or superseded while we
                // were talking to Google. Theirs wins; ours is stale by definition.
                return false;
            }
            acknowledgeIfOwed(row, google, token);
            writer.find(row.getId()).ifPresent(refreshed -> linkResolver.resolve(refreshed, verifiedAt));
            return true;
        } catch (PlayApiException unreachable) {
            // Transport, 5xx, timeout: LEAVE THE ROW ENTIRELY ALONE. verified_at does not move, so
            // it stays at the head of the queue and is retried in fifteen minutes.
            log.warn("Reconcile could not reach Google for subscription {}: {}",
                    PurchaseTokens.digest(token), unreachable.getMessage());
            return false;
        } catch (PlayPurchaseInvalidException refused) {
            // Google answered, and the answer was 404 or 400. NEVER REVOKE on it: a 400 can be our
            // own bug, and letting a Google error remove a paid entitlement is a far worse failure
            // than leaving a bogus row entitling — the fail-closed entitled_until predicate ends it
            // on its own anyway.
            //
            // verified_at IS bumped, and only that: a definitive answer is still Google answering,
            // which is what the column means. Without the bump this row would sit at the head of
            // every batch forever and starve the sweep.
            log.warn("Reconcile: Google refused subscription {} ({}); bumping verified_at and revoking nothing",
                    PurchaseTokens.digest(token), refused.getMessage());
            writer.touchVerifiedAt(row.getId(), Instant.now());
            return false;
        } catch (RuntimeException unexpected) {
            log.warn("Reconcile skipped subscription {} after an unexpected failure; the batch continues",
                    PurchaseTokens.digest(token), unexpected);
            writer.touchVerifiedAt(row.getId(), Instant.now());
            return false;
        }
    }

    /**
     * The acknowledgement retry, OUTSIDE every transaction, then recorded in its own.
     *
     * <p>Only for a row that still entitles: acknowledging an expired purchase achieves nothing, and
     * the 3-day (5-minute, on the closed track) auto-refund clock only runs while the purchase is
     * live.
     */
    private void acknowledgeIfOwed(UserSubscription row, PlaySubscription google, String token) {
        if (google.acknowledged() || !row.entitlesAt(Instant.now())) {
            return;
        }
        try {
            play.acknowledge(row.getProductId(), token);
            writer.markAcknowledged(row.getId());
            log.info("Reconcile acknowledged subscription {} of user {} — Google would have auto-refunded it",
                    PurchaseTokens.digest(token), row.getUserId());
        } catch (RuntimeException failed) {
            log.warn("Reconcile could not acknowledge subscription {}: {}",
                    PurchaseTokens.digest(token), failed.getMessage());
        }
    }

    /**
     * THE CEILING, made observable. A full batch plus an oldest candidate past twice the staleness
     * threshold is what "the sweep can no longer keep up" looks like from the inside — and without
     * this line it looks like nothing at all: every run succeeds, every row it touches is repaired,
     * and the rows at the back of the queue simply never come round. See
     * {@link ReconcileProperties} for the arithmetic (about 1,200 live subscriptions).
     */
    private void warnIfFallingBehind(List<UserSubscription> candidates, ReconcileProperties.Reconcile config,
                                     Instant now) {
        if (candidates.size() < config.batchSize()) {
            return;
        }
        Instant oldest = candidates.stream()
                .map(UserSubscription::getVerifiedAt)
                .min(Instant::compareTo)
                .orElse(now);
        if (oldest.isBefore(now.minus(config.staleAfter().multipliedBy(2)))) {
            log.warn("Subscription reconcile is falling behind: a full batch of {} and the oldest candidate "
                            + "was last verified at {}. Raise whereis.play.reconcile.batch-size or lower "
                            + "the delay.",
                    config.batchSize(), oldest);
        }
    }

    /** Paces the Google calls inside one run; see {@link ReconcileProperties} for the budget. */
    private static void pause(java.time.Duration interval) {
        try {
            Thread.sleep(Math.max(0L, interval.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
