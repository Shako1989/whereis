package az.technest.whereis.plan.reconcile;

import az.technest.whereis.plan.PurchaseTokens;
import az.technest.whereis.plan.SubscriptionWriter;
import az.technest.whereis.plan.UserSubscription;
import az.technest.whereis.plan.UserSubscriptionRepository;
import az.technest.whereis.plan.play.PlayApiException;
import az.technest.whereis.plan.play.PlayVoidedPage;
import az.technest.whereis.plan.play.PlayVoidedPurchase;
import az.technest.whereis.plan.play.PlaySubscriptionsApi;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The refund backstop: every six hours, ask Google for the voids of the last seven days and apply
 * any this server has not already recorded.
 *
 * <p><strong>Two independent entry points, because either alone loses money.</strong> A
 * {@code voidedPurchaseNotification} is immediate but can be lost (Pub/Sub retention, a week-long
 * outage, a message we gave up on, or a void that arrived before the purchase was known to us);
 * this sweep catches everything the notification missed but only runs four times a day. Missing
 * both means an annual subscriber who refunds on day two keeps their tier for twelve months.
 *
 * <p>Both entry points converge on the SAME {@code SubscriptionWriter#markVoided}, with the same
 * write-once rule and the same untouched fields — which is why they cannot disagree. The only
 * difference is the last argument: this one passes {@code null} for {@code eventTimeMillis},
 * because a sweep applies no notification and must not touch the RTDN high-water mark.
 *
 * <p>No {@code @Transactional} on this class or its methods: the Google calls must not hold a
 * connection. See {@code SubscriptionReconciler} for why that is asserted directly rather than
 * left to the ArchUnit rule.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoidedPurchaseSweeper {

    private final UserSubscriptionRepository subscriptions;
    private final SubscriptionWriter writer;
    private final PlaySubscriptionsApi play;
    private final ReconcileProperties properties;

    /**
     * Every six hours at a deliberately odd minute, so it never starts in the same second as
     * anything else on the box.
     */
    @Scheduled(cron = "${whereis.play.voided-sweep.cron:0 17 */6 * * *}")
    public void sweep() {
        if (!Boolean.TRUE.equals(properties.voidedSweep().enabled())) {
            return;
        }
        runOnce();
    }

    /**
     * ONE PASS, without consulting the {@code enabled} flag — see
     * {@link PlayCancellationJanitor#runOnce()} for why the integration suite needs it.
     */
    public void runOnce() {
        ReconcileProperties.VoidedSweep config = properties.voidedSweep();
        Instant end = Instant.now();
        Instant start = end.minus(config.lookback());
        String pageToken = null;
        int pages = 0;
        int applied = 0;
        int seen = 0;
        try {
            do {
                if (pages > 0) {
                    pause(config.minInterval());
                }
                PlayVoidedPage page = play.listVoidedPurchases(start, end, pageToken);
                pages++;
                for (PlayVoidedPurchase voided : page.purchases()) {
                    seen++;
                    if (apply(voided)) {
                        applied++;
                    }
                }
                pageToken = page.nextPageToken();
            } while (pageToken != null && !pageToken.isBlank() && pages < config.maxPages());
        } catch (PlayApiException unreachable) {
            // The backstop failing is not an emergency — the next run is six hours away and the
            // window is a fixed seven days, so nothing is lost by stopping here.
            log.warn("Voided-purchase sweep could not reach Google after {} pages: {}",
                    pages, unreachable.getMessage());
        }
        if (pageToken != null && !pageToken.isBlank() && pages >= config.maxPages()) {
            // A week produced more than maxPages × Google's page size refunds. That is a business
            // event long before it is an engineering one, which is why it is a WARN and not a
            // silently raised limit.
            log.warn("Voided-purchase sweep stopped at its {}-page cap with more pages available",
                    config.maxPages());
        }
        if (applied > 0 || seen > 0) {
            log.info("Voided-purchase sweep: {} pages, {} voids in the window, {} newly applied",
                    pages, seen, applied);
        }
    }

    /**
     * Apply one void, if there is a local row and it is not already voided.
     *
     * <p>{@code findByPurchaseToken} is the deliberately unscoped finder, and this is the second
     * legitimate use of it: Google's list carries a token and no account, there is no userId to
     * scope by, and inventing one is the failure the whole design avoids.
     *
     * <p>Every applied revoke is logged at WARN with the digest, the userId, the source and
     * Google's own {@code voidedReason}/{@code voidedSource}. An erroneous revoke is otherwise
     * permanent and invisible — {@code voided_at} is write-once, a REFRESH may never clear it, and
     * the reconciler refuses to run against a voided row — so being able to FIND it is the
     * difference between a repairable mistake and an unrepairable one. The sanctioned repair is in
     * {@code deploy/README.md}.
     */
    private boolean apply(PlayVoidedPurchase voided) {
        if (voided.purchaseToken() == null || voided.purchaseToken().isBlank()) {
            return false;
        }
        Optional<UserSubscription> row = subscriptions.findByPurchaseToken(voided.purchaseToken());
        if (row.isEmpty() || row.get().getVoidedAt() != null) {
            return false;
        }
        Instant voidedAt = voided.voidedAt() == null ? Instant.now() : voided.voidedAt();
        // null eventTimeMillis: a sweep applies no notification, so it must not move the watermark.
        boolean first = writer.markVoided(row.get().getId(), voidedAt, voided.orderId(), null);
        if (first) {
            log.warn("REVOKED subscription {} of user {} (voided sweep): reason={} source={}",
                    PurchaseTokens.digest(voided.purchaseToken()), row.get().getUserId(),
                    voided.voidedReason(), voided.voidedSource());
        }
        return first;
    }

    private static void pause(java.time.Duration interval) {
        try {
            Thread.sleep(Math.max(0L, interval.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
