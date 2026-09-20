package az.technest.whereis.plan.reconcile;

import az.technest.whereis.common.legal.LegalProperties;
import az.technest.whereis.plan.rtdn.PlayNotificationPurgeService;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ages rows out of {@code play_notifications}, the RTDN ledger — which until now had no deletion
 * path of any kind and therefore kept every purchase token, order id, product id and raw Google
 * payload it had ever been sent, indefinitely.
 *
 * <p>It is the second half of a pair. {@code AccountDeletionService} removes the rows that can
 * still be attributed to the account being deleted; this removes the rest by age, which is the only
 * thing that can reach a notification for a token no account ever claimed, or one that arrived
 * AFTER the deletion, when its token no longer resolves to anybody. See
 * {@link PlayNotificationPurgeService} for why neither alone is enough.
 *
 * <p><strong>The one scheduled billing component that does NOT check
 * {@code PlayProperties#billingConfigured()}.</strong> The other three make Play API calls, so under
 * {@code whereis.play.provider=disabled} a tick would throw on its first row forever. This one
 * makes no Play call — it is one {@code DELETE} — and a deployment that switches billing off must
 * still purge the ledger it accumulated while billing was on. Gating it on the provider would turn
 * "billing is off" into "the retention promise stopped being kept", silently.
 *
 * <p>No {@code @Transactional} on this class or its methods; see {@code SubscriptionReconciler}.
 */
@Slf4j
@Component
public class PlayNotificationJanitor {

    /**
     * <strong>The floor, and the reason it is a startup failure rather than a clamp.</strong> Cloud
     * Pub/Sub retains an unacknowledged message for up to 7 days, and the ledger's primary key —
     * Google's message id — is the entire mechanism that makes a redelivery a no-op. A window at or
     * under 7 days would delete a row while Pub/Sub could still redeliver its message, so the
     * redelivery would insert a fresh row and be processed a second time. Clamping silently would
     * hide an operator's mistake behind a number the public page then states wrongly; refusing to
     * boot names it.
     */
    private static final Duration MINIMUM_WINDOW = Duration.ofDays(7);

    private final PlayNotificationPurgeService purge;
    private final ReconcileProperties properties;
    private final Duration window;

    public PlayNotificationJanitor(PlayNotificationPurgeService purge, ReconcileProperties properties,
                                   LegalProperties legal) {
        this.purge = purge;
        this.properties = properties;
        this.window = windowOf(legal);
    }

    /**
     * An {@code initialDelay} as well as an {@code enabled} flag, for the reasons
     * {@code PlayCancellationJanitor} spells out: {@code @EnableScheduling} is global, so a plain
     * {@code fixedDelay} job fires at context startup — including in every Testcontainers context,
     * where it would sweep whatever a test had just inserted.
     */
    @Scheduled(fixedDelayString = "${whereis.play.notification-retention.delay:PT24H}",
            initialDelayString = "${whereis.play.notification-retention.initial-delay:PT5M}")
    public void sweep() {
        if (!Boolean.TRUE.equals(properties.notificationRetention().enabled())) {
            return;
        }
        runOnce();
    }

    /**
     * ONE PASS, without consulting the {@code enabled} flag — the shape every scheduled component
     * in this package has, so the integration suite can drive a single sweep with the flag off.
     */
    public void runOnce() {
        Instant cutoff = Instant.now().minus(window);
        int removed = purge.purgeReceivedBefore(cutoff);
        if (removed > 0) {
            // INFO and only when something moved: a daily line saying "deleted 0" is the noise that
            // trains an operator to stop reading the log.
            log.info("Purged {} Play notification(s) received before {} ({}-day retention)",
                    removed, cutoff, window.toDays());
        }
    }

    /** The window the public pages state, validated at startup rather than at the first sweep. */
    private static Duration windowOf(LegalProperties legal) {
        String configured = legal.billingLogRetentionDays();
        long days;
        try {
            days = Long.parseLong(configured == null ? "" : configured.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalStateException("whereis.legal.billing-log-retention-days must be a whole number"
                    + " of days (WHEREIS_LEGAL_BILLING_LOG_RETENTION_DAYS), not \"" + configured + "\"",
                    notANumber);
        }
        Duration window = Duration.ofDays(days);
        if (window.compareTo(MINIMUM_WINDOW) <= 0) {
            throw new IllegalStateException("whereis.legal.billing-log-retention-days is " + days
                    + " but must exceed " + MINIMUM_WINDOW.toDays() + ": Cloud Pub/Sub redelivers an"
                    + " unacknowledged message for up to that long, and play_notifications.message_id is"
                    + " what makes a redelivery a no-op. A shorter window would re-apply notifications.");
        }
        return window;
    }
}
