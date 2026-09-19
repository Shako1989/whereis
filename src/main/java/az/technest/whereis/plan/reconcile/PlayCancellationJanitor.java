package az.technest.whereis.plan.reconcile;

import az.technest.whereis.common.legal.LegalProperties;
import az.technest.whereis.plan.PlayCancellationQueueEntry;
import az.technest.whereis.plan.PlayCancellationQueueRepository;
import az.technest.whereis.plan.PurchaseTokens;
import az.technest.whereis.plan.UserSubscriptionRepository;
import az.technest.whereis.plan.play.PlayPurchaseUnknownException;
import az.technest.whereis.plan.play.PlaySubscriptionsApi;
import az.technest.whereis.storage.StorageJanitor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains {@code play_cancellation_queue}: tells Google to stop auto-renewing the subscriptions of
 * accounts that have been deleted.
 *
 * <p>A line-for-line sibling of {@code StorageJanitor}, down to sharing its backoff schedule rather
 * than copying it — {@link StorageJanitor#backoff(int)} is the one answer this codebase has for
 * "commit with the database, then act on an external system", and two copies of a retry schedule
 * drift.
 *
 * <p>No {@code @Transactional} on this class or its methods; see {@code SubscriptionReconciler}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlayCancellationJanitor {

    private final PlayCancellationQueueRepository queue;
    private final UserSubscriptionRepository subscriptions;
    private final PlaySubscriptionsApi play;
    private final ReconcileProperties properties;
    private final LegalProperties legal;

    /**
     * An {@code initialDelay} as well as an {@code enabled} flag, and both matter.
     * {@code @EnableScheduling} is global, so a plain {@code fixedDelay} job with no initial delay
     * fires at context startup — including in every Testcontainers integration context, where it
     * would drain whatever the test just enqueued before the test could look at it, making
     * {@code AccountDeletionIT} order-dependent. The flag is also the single-instance mitigation:
     * the operator's answer to "we are now running two" is to turn the sweeps off on all but one.
     */
    @Scheduled(fixedDelayString = "${whereis.play.cancellation.delay:PT5M}",
            initialDelayString = "${whereis.play.cancellation.initial-delay:PT2M}")
    public void sweep() {
        if (!Boolean.TRUE.equals(properties.cancellation().enabled())) {
            return;
        }
        runOnce();
    }

    /**
     * ONE PASS, without consulting the {@code enabled} flag. The integration suite turns the flag
     * off in the shared context — {@code @EnableScheduling} is global, so a background sweep would
     * drain whatever a test had just enqueued — and drives this directly instead, which is also the
     * only way to assert the effect of a SINGLE pass.
     */
    public void runOnce() {
        ReconcileProperties.Cancellation config = properties.cancellation();
        Instant now = Instant.now();
        List<PlayCancellationQueueEntry> due =
                queue.findDue(now, PageRequest.of(0, config.batchSize()));
        for (PlayCancellationQueueEntry entry : due) {
            drain(entry, now);
        }
    }

    private void drain(PlayCancellationQueueEntry entry, Instant now) {
        String token = entry.getPurchaseToken();
        if (rebound(entry, token)) {
            return;
        }
        if (expired(entry, now, token)) {
            return;
        }
        try {
            play.cancel(entry.getProductId(), token);
            queue.deleteById(entry.getId());
            log.info("Cancelled Play subscription {} ({}) for a deleted account",
                    PurchaseTokens.digest(token), entry.getProductId());
        } catch (PlayPurchaseUnknownException gone) {
            // 404 ONLY. Google does not have this purchase — already cancelled, already expired, or
            // never ours — so there is nothing left to do and the row goes.
            queue.deleteById(entry.getId());
            log.info("Play does not know subscription {}; nothing left to cancel",
                    PurchaseTokens.digest(token));
        } catch (RuntimeException failure) {
            // EVERYTHING ELSE RETRIES, including a 400. purchases.subscriptions.cancel is the v1
            // endpoint and takes OUR stored product id, which can legitimately disagree with the
            // token's current product (a re-pointed whereis.plans.*.product-id, or a multi-line-item
            // purchase whose highest tier we deliberately stored). Deleting the row on a 400 would
            // discard the cancellation permanently and leave Google auto-renewing a subscription
            // whose account no longer exists — the exact harm this whole component exists to
            // prevent, and the opposite of the reconciler's ruling on the same exception class.
            entry.setAttempts(entry.getAttempts() + 1);
            entry.setNextAttemptAt(now.plus(StorageJanitor.backoff(entry.getAttempts())));
            entry.setLastError(truncate(failure.getMessage()));
            queue.save(entry);
            log.warn("Cancellation retry {} failed for subscription {} ({}): {}",
                    entry.getAttempts(), PurchaseTokens.digest(token), entry.getProductId(),
                    failure.getMessage());
        }
    }

    /**
     * <strong>The token may belong to a LIVE account again.</strong> The queue row carries no user
     * and no foreign key by design, so nothing else would notice: the person deletes their account
     * (T is enqueued), re-registers — which the legal page explicitly invites them to do — opens the
     * app, and wave 1's {@code PurchaseSyncer} posts T on the FIRST foreground, because
     * {@code queryPurchasesAsync} still returns it for the rest of the paid term. Cancelling then
     * turns auto-renew off for somebody who did not ask, with no notification and nothing on the
     * legal page warning them.
     *
     * <p>So: re-read the token first (the unscoped finder again, and a legitimate use of it). A live
     * non-voided row means the purchase has been re-bound; drop the queue row WITHOUT cancelling.
     */
    private boolean rebound(PlayCancellationQueueEntry entry, String token) {
        boolean live = subscriptions.findByPurchaseToken(token)
                .filter(row -> row.getVoidedAt() == null)
                .isPresent();
        if (live) {
            queue.deleteById(entry.getId());
            log.info("Subscription {} belongs to a live account again; not cancelling it",
                    PurchaseTokens.digest(token));
        }
        return live;
    }

    /**
     * The give-up deadline — and the ONE number on this class that is not ours to choose.
     *
     * <p>It is {@code whereis.legal.cancellation-retry-days}, the same property the public
     * account-deletion page is rendered from, in both languages. A purchase token belonging to an
     * account that no longer exists may not be retained indefinitely, the page states how long, and
     * binding both to one property is what stops the page from lying. {@code LegalPages} already
     * refuses to boot while the property is unset, which is the cross-check for free.
     *
     * <p>ERROR, not WARN: this is the one case in this wave where a human must act. The account and
     * its e-mail address are gone, so there is no channel left to tell the user — an operator
     * cancels it by hand in the Play Console from the digest and product id in this line. The runbook
     * names it as an alert.
     */
    private boolean expired(PlayCancellationQueueEntry entry, Instant now, String token) {
        Duration limit = Duration.ofDays(Long.parseLong(legal.cancellationRetryDays().trim()));
        if (entry.getCreatedAt() == null || entry.getCreatedAt().isAfter(now.minus(limit))) {
            return false;
        }
        queue.deleteById(entry.getId());
        log.error("GAVE UP cancelling Play subscription {} ({}) after {} days — Google may still be "
                        + "charging a deleted account. Cancel it by hand in the Play Console. Last error: {}",
                PurchaseTokens.digest(token), entry.getProductId(), limit.toDays(), entry.getLastError());
        return true;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
