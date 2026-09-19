package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.SubscriptionState;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.plan.play.FakePlaySubscriptionsApi;
import az.technest.whereis.plan.play.PlaySubscriptionsApi;
import az.technest.whereis.plan.play.PlayVoidedPurchase;
import az.technest.whereis.plan.reconcile.VoidedPurchaseSweeper;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.CreateSpaceRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * Refunds and chargebacks end to end, through BOTH entry points.
 *
 * <p>Either one alone has a failure mode that costs real money: the notification can be lost
 * (Pub/Sub retention, a week-long outage, a message we gave up on) and the sweep only runs every
 * six hours. Missing both means an annual subscriber who refunds on day two keeps their tier for
 * twelve months.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlayVoidedPurchaseIT extends AbstractIntegrationTest {

    @Autowired
    private PlaySubscriptionsApi play;
    /** Driven by hand: the scheduled flag is off in the shared IT context. */
    @Autowired
    private VoidedPurchaseSweeper sweeper;

    @BeforeEach
    void resetTheFakePort() {
        ((FakePlaySubscriptionsApi) play).reset();
    }

    @Test
    void aRefundDropsAnEntitlingProAccountToFreeOnTheVeryNextReadWithoutDeletingAnything() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-active-pro-" + UUID.randomUUID();
        seedSubscription(userId, Plan.PRO, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)), null, null, purchaseToken);
        createSpace(token, "Home", SpaceType.HOME);
        createSpace(token, "Office", SpaceType.OFFICE);
        assertThat(planOf(token).plan()).isEqualTo(Plan.PRO);

        String messageId = "it-" + UUID.randomUUID();
        assertThat(postRtdn(messageId, voidedNotification(Instant.now().toEpochMilli(), purchaseToken, 1))
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(ledgerOutcome(messageId)).isEqualTo("APPLIED");
        PlanStatusResponse after = planOf(token);
        assertThat(after.plan()).isEqualTo(Plan.FREE);
        // NOTHING EXISTING IS DELETED. An account at two spaces on PRO that gets refunded keeps
        // both, keeps reading and renaming them, and is refused only CREATION of a third.
        assertThat(after.usage().spaces()).isEqualTo(2L);
        // There is nothing left to manage and nothing to fix, so the strip disappears too.
        assertThat(after.subscription()).isNull();

        assertThat(post(token, "/api/v1/spaces", new CreateSpaceRequest("Garage", null, SpaceType.GARAGE),
                JsonNode.class))
                .satisfies(refused -> {
                    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refused.getBody().get("code").asText()).isEqualTo("PLAN_LIMIT_REACHED");
                });
    }

    @Test
    void aRefundWhoseEventTimeIsBelowTheRowsWatermarkStillRevokes() {
        // A refund and its accompanying SUBSCRIPTION_CANCELED are emitted within milliseconds of
        // each other and Pub/Sub guarantees no order. If the cancellation lands first, a
        // watermark-guarded revoke would be DISCARDED — leaving the row CANCELED with a future
        // expiry, which is one of the three ENTITLING states, so a refunded annual subscriber would
        // keep PRO until the six-hourly sweep happened to repair it.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-active-pro-" + UUID.randomUUID();
        UUID rowId = seedSubscription(userId, Plan.PRO, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)), null, null, purchaseToken);
        long watermark = Instant.now().toEpochMilli();
        jdbc.update("update user_subscriptions set last_event_time = ? where id = ?", watermark, rowId);

        String messageId = "it-" + UUID.randomUUID();
        assertThat(postRtdn(messageId, voidedNotification(watermark - 5_000, purchaseToken, 1))
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(ledgerOutcome(messageId)).isEqualTo("APPLIED");
        assertThat(planOf(token).plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void aSecondNotificationForTheSameRefundChangesNothingAndIsRecordedAsDiscarded() {
        // A DIFFERENT messageId — a true redelivery of the SAME messageId never reaches the handler
        // at all (PlayRtdnIT covers that). Write-once on voided_at is what makes this safe.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-active-pro-" + UUID.randomUUID();
        UUID rowId = seedSubscription(userId, Plan.PRO, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)), null, null, purchaseToken);
        long at = Instant.now().toEpochMilli();
        postRtdn("it-" + UUID.randomUUID(), voidedNotification(at, purchaseToken, 1));
        Instant firstVoidedAt = voidedAtOf(rowId);

        String second = "it-" + UUID.randomUUID();
        assertThat(postRtdn(second, voidedNotification(at - 1_000, purchaseToken, 1)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(ledgerOutcome(second)).isEqualTo("DISCARDED_STALE");
        assertThat(voidedAtOf(rowId)).isEqualTo(firstVoidedAt);
    }

    @Test
    void aVoidWithNoProductTypeFieldIsStillApplied() {
        // `productType != 1` fails OPEN: an absent field makes the test true and every refund is
        // silently ignored, which is the exact fail-open shape this path exists to prevent, one
        // level down. A hit in user_subscriptions IS a subscription void by construction.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-active-pro-" + UUID.randomUUID();
        seedSubscription(userId, Plan.PRO, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)), null, null, purchaseToken);

        String messageId = "it-" + UUID.randomUUID();
        postRtdn(messageId, voidedNotification(Instant.now().toEpochMilli(), purchaseToken, null));

        assertThat(ledgerOutcome(messageId)).isEqualTo("APPLIED");
        assertThat(planOf(token).plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void theSweepCatchesARefundWhoseNotificationWasNeverDelivered() {
        // THE BACKSTOP. Nothing is posted to /play/rtdn here at all — this is the outage case, and
        // it is the reason both entry points exist.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-active-pro-" + UUID.randomUUID();
        seedSubscription(userId, Plan.PRO, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)), null, null, purchaseToken);
        ((FakePlaySubscriptionsApi) play).enqueueVoid(new PlayVoidedPurchase(purchaseToken,
                "GS.0000-0000-0001", Instant.now().minus(Duration.ofDays(2)), 1, 0));

        sweeper.runOnce();

        assertThat(planOf(token).plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void theSweepLeavesTheRtdnWatermarkAloneBecauseItAppliesNoNotification() {
        // Writing it would push the high-water mark ahead of notifications still in flight and the
        // handler would discard them all, silently and unrecoverably.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-active-pro-" + UUID.randomUUID();
        UUID rowId = seedSubscription(userId, Plan.PRO, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)), null, null, purchaseToken);
        ((FakePlaySubscriptionsApi) play).enqueueVoid(new PlayVoidedPurchase(purchaseToken,
                "GS.0000-0000-0002", Instant.now().minus(Duration.ofHours(3)), 1, 0));

        sweeper.runOnce();

        assertThat(jdbc.queryForObject("select last_event_time from user_subscriptions where id = ?",
                Long.class, rowId))
                .isNull();
        assertThat(voidedAtOf(rowId)).isNotNull();
    }

    private PlanStatusResponse planOf(String token) {
        return get(token, "/api/v1/users/me/plan", PlanStatusResponse.class).getBody();
    }

    private String ledgerOutcome(String messageId) {
        return jdbc.queryForObject("select outcome from play_notifications where message_id = ?",
                String.class, messageId);
    }

    private Instant voidedAtOf(UUID rowId) {
        java.sql.Timestamp at = jdbc.queryForObject(
                "select voided_at from user_subscriptions where id = ?", java.sql.Timestamp.class, rowId);
        return at == null ? null : at.toInstant();
    }
}
