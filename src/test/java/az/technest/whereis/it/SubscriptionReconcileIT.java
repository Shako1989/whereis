package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.SubscriptionState;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.plan.play.FakePlaySubscriptionsApi;
import az.technest.whereis.plan.play.PlaySubscriptionsApi;
import az.technest.whereis.plan.reconcile.SubscriptionReconciler;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The reconciler against real PostgreSQL: drift repair, the acknowledgement retry, and the guards
 * that stop it from undoing the RTDN handler's work.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SubscriptionReconcileIT extends AbstractIntegrationTest {

    @Autowired
    private PlaySubscriptionsApi play;
    /** Driven by hand: the scheduled flag is off in the shared IT context. */
    @Autowired
    private SubscriptionReconciler reconciler;

    @BeforeEach
    void resetTheFakePort() {
        ((FakePlaySubscriptionsApi) play).reset();
    }

    @Test
    void anAccountWhoseNotificationWasNeverDeliveredHasItsTierCorrectedByOneSweep() {
        // The drift case. Nothing is posted to /play/rtdn: this is what the reconciler is FOR.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-expired-pro-" + UUID.randomUUID();
        UUID rowId = stale(userId, purchaseToken, SubscriptionState.ACTIVE, Plan.PRO, true);
        assertThat(planOf(token).plan()).isEqualTo(Plan.PRO);

        reconciler.runOnce();

        assertThat(stateOf(rowId)).isEqualTo("EXPIRED");
        assertThat(planOf(token).plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void anUnacknowledgedRowIsAcknowledgedAndTheFakeRecordsTheToken() {
        // The wave-1 hole: one transient 5xx during acknowledge left an account this database says
        // is entitled for a year and Google has silently refunded — after 3 days, or 5 MINUTES for
        // a test purchase, which is every purchase on the closed track.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-active-pro-" + UUID.randomUUID();
        UUID rowId = stale(userId, purchaseToken, SubscriptionState.ACTIVE, Plan.PRO, false);

        reconciler.runOnce();

        assertThat(((FakePlaySubscriptionsApi) play).acknowledgedTokens()).contains(purchaseToken);
        assertThat(jdbc.queryForObject("select acknowledged from user_subscriptions where id = ?",
                Boolean.class, rowId))
                .isTrue();
    }

    @Test
    void anUnacknowledgedRowIsSweptEvenWhenItWasVerifiedSecondsAgo() {
        // Unacknowledged rows come FIRST and ignore the staleness clock entirely, because the
        // auto-refund window is measured in minutes on a closed track and the staleness window in
        // hours.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-active-pro-" + UUID.randomUUID();
        UUID rowId = seedSubscription(userId, Plan.PRO, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)), null, null, purchaseToken);
        jdbc.update("update user_subscriptions set acknowledged = false, verified_at = now() where id = ?",
                rowId);

        reconciler.runOnce();

        assertThat(((FakePlaySubscriptionsApi) play).acknowledgedTokens()).contains(purchaseToken);
    }

    @Test
    void aVoidedRowIsNeverUnVoidedNoMatterWhatGoogleSays() {
        // The chargeback case, and the fourth anti-fight mechanism. The fake reports this token as
        // ACTIVE with a year to run; the row must stay voided, because voided_at is write-once and
        // reconcile refuses to run against a voided row at all.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-active-pro-" + UUID.randomUUID();
        UUID rowId = stale(userId, purchaseToken, SubscriptionState.ACTIVE, Plan.PRO, true);
        jdbc.update("update user_subscriptions set voided_at = now() - interval '1 hour' where id = ?", rowId);

        reconciler.runOnce();

        assertThat(jdbc.queryForObject("select voided_at from user_subscriptions where id = ?",
                java.sql.Timestamp.class, rowId))
                .isNotNull();
        assertThat(planOf(token).plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void theReconcilerNeverWritesTheRtdnWatermark() {
        // Writing it would push the high-water mark ahead of notifications still in flight for this
        // purchase, and the handler would discard them all — the exact trap V10 documented for the
        // verify endpoint, which applies to this component word for word.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-canceled-pro-" + UUID.randomUUID();
        UUID rowId = stale(userId, purchaseToken, SubscriptionState.ACTIVE, Plan.PRO, true);

        reconciler.runOnce();

        assertThat(stateOf(rowId)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("select last_event_time from user_subscriptions where id = ?",
                Long.class, rowId))
                .isNull();
    }

    @Test
    void aTokenGoogleRefusesIsNeverRevokedAndOnlyItsVerifiedAtMoves() {
        // GooglePlaySubscriptionsApi maps 400 as well as 404 to this exception, and a 400 can be
        // OUR bug. Letting a Google error remove a paid entitlement is a far worse failure than
        // leaving a bogus row entitling; entitled_until ends it on its own.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "totally-unknown-" + UUID.randomUUID();
        UUID rowId = stale(userId, purchaseToken, SubscriptionState.ACTIVE, Plan.PRO, true);

        reconciler.runOnce();

        assertThat(jdbc.queryForObject("select voided_at from user_subscriptions where id = ?",
                java.sql.Timestamp.class, rowId))
                .isNull();
        assertThat(stateOf(rowId)).isEqualTo("ACTIVE");
        assertThat(planOf(token).plan()).isEqualTo(Plan.PRO);
        // ...but verified_at moved, or this row would sit at the head of every batch forever.
        assertThat(jdbc.queryForObject(
                "select verified_at > now() - interval '1 minute' from user_subscriptions where id = ?",
                Boolean.class, rowId))
                .isTrue();
    }

    @Test
    void anOperatorGrantWithNoTokenIsNeverSweptBecauseThereIsNothingAtGoogleToAskAbout() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        UUID rowId = UUID.randomUUID();
        jdbc.update("insert into user_subscriptions (id, user_id, purchase_token, product_id, tier,"
                        + " provenance, state, entitled_until, acknowledged, test_purchase, verified_at)"
                        + " values (?, ?, null, null, 'PRO', 'OPERATOR', 'ACTIVE', now() + interval '365 days',"
                        + " true, false, now() - interval '3 days')",
                rowId, userId);

        reconciler.runOnce();

        assertThat(jdbc.queryForObject(
                "select verified_at < now() - interval '2 days' from user_subscriptions where id = ?",
                Boolean.class, rowId))
                .isTrue();
    }

    /** A row Google has not confirmed in three days: a candidate on the staleness branch. */
    private UUID stale(UUID userId, String purchaseToken, SubscriptionState state, Plan tier,
                       boolean acknowledged) {
        UUID rowId = seedSubscription(userId, tier, state,
                Instant.now().plus(Duration.ofDays(365)), null, null, purchaseToken);
        jdbc.update("update user_subscriptions set verified_at = now() - interval '3 days',"
                + " acknowledged = ? where id = ?", acknowledged, rowId);
        return rowId;
    }

    private PlanStatusResponse planOf(String token) {
        return get(token, "/api/v1/users/me/plan", PlanStatusResponse.class).getBody();
    }

    private String stateOf(UUID rowId) {
        return jdbc.queryForObject("select state from user_subscriptions where id = ?", String.class, rowId);
    }
}
