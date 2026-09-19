package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.SubscriptionState;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.plan.dto.PurchaseVerificationRequest;
import az.technest.whereis.plan.play.FakePlaySubscriptionsApi;
import az.technest.whereis.plan.play.PlayAccountHash;
import az.technest.whereis.plan.play.PlaySubscriptionsApi;
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
import org.springframework.http.ResponseEntity;

/**
 * Upgrades and downgrades, server side.
 *
 * <p>The governing decision, restated because every case here depends on it: <strong>the server
 * never learns the replacement mode and must not need to.</strong> The client chooses one, Google
 * applies it, and the server sees either a new token whose {@code linkedPurchaseToken} names the old
 * one or the same token with a changed product at renewal. Both are answered by the two rules the
 * server already has — refresh from Google, then resolve the link — so there is no branch anywhere
 * on "was this an upgrade or a downgrade", and no place for the client's intent and the server's
 * belief to disagree.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlanTierChangeIT extends AbstractIntegrationTest {

    private static final String PURCHASES = "/api/v1/users/me/plan/purchases";

    @Autowired
    private PlaySubscriptionsApi play;

    @BeforeEach
    void resetTheFakePort() {
        ((FakePlaySubscriptionsApi) play).reset();
    }

    @Test
    void anUpgradeSupersedesTheOldRowWhichStopsEntitlingInTheSameRequest() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        // The fake's `linked` shape reports linkedPurchaseToken = "<token>-previous", so the OLD
        // row has to be created under exactly that token for the link to resolve.
        String newToken = "fake-linked-max-" + UUID.randomUUID();
        String oldToken = newToken + "-previous";
        UUID oldRow = seedSubscription(userId, Plan.STANDARD, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(200)), null, null, oldToken);
        assertThat(planOf(token).plan()).isEqualTo(Plan.STANDARD);

        assertThat(buy(token, newToken, "whereis_max_annual").getStatusCode()).isEqualTo(HttpStatus.OK);

        // supersededBy IS NULL is the fourth predicate in entitlingOf, so the old row stops
        // entitling the moment it is pointed at — no second request, no scheduled job.
        assertThat(supersededByOf(oldRow)).isNotNull();
        assertThat(planOf(token).plan()).isEqualTo(Plan.MAX);
        // NOTHING THE USER OWNS IS DELETED, and the superseded row is still readable.
        assertThat(count("select count(*) from user_subscriptions where user_id = ?", userId))
                .isEqualTo(2);
    }

    @Test
    void anUpgradeSeenFirstByTheRtdnHandlerReachesTheIdenticalRowState() {
        // BOTH ENTRY POINTS, ONE OUTCOME. The RTDN often arrives before the client's next
        // foreground, so the handler may create the row — and the result has to be the same row
        // state either way, or the two writers disagree about what the account holds.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String hash = PlayAccountHash.of(userId);
        String newToken = "fake-linked-max-" + UUID.randomUUID() + "@" + hash;
        String oldToken = newToken + "-previous";
        UUID oldRow = seedSubscription(userId, Plan.STANDARD, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(200)), null, null, oldToken);

        String messageId = "it-" + UUID.randomUUID();
        assertThat(postRtdn(messageId, subscriptionNotification(Instant.now().toEpochMilli(), 4, newToken))
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(ledgerOutcome(messageId)).isEqualTo("APPLIED");
        assertThat(supersededByOf(oldRow)).isNotNull();
        assertThat(planOf(token).plan()).isEqualTo(Plan.MAX);
        assertThat(jdbc.queryForObject(
                "select user_id from user_subscriptions where purchase_token = ?", UUID.class, newToken))
                .isEqualTo(userId);
    }

    @Test
    void anUpgradeWhoseNewRowDoesNotYetEntitleSupersedesNothing() {
        // A deferred-payment purchase can be PENDING while the old one is still ACTIVE. Superseding
        // then would strip a user of entitlement they have ALREADY PAID FOR.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String newToken = "fake-pending-max-" + UUID.randomUUID();
        String oldToken = newToken + "-previous";
        UUID oldRow = seedSubscription(userId, Plan.STANDARD, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(200)), null, null, oldToken);

        // A PENDING purchase is always 409 (retryable, token kept) — never a drop-the-token 400.
        assertThat(buy(token, newToken, "whereis_max_annual").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(supersededByOf(oldRow)).isNull();
        assertThat(planOf(token).plan()).isEqualTo(Plan.STANDARD);
    }

    @Test
    void anUpgradeLinkedToAnotherAccountsTokenIsNeverStitchedAcrossUsers() {
        // V10's composite self-FK (superseded_by, user_id) -> (id, user_id) is what makes a
        // cross-user chain unrepresentable, because linkedPurchaseToken is scoped to a PLAY account
        // rather than a whereis account and one Play account can be signed into two whereis
        // accounts. Resolving with the global finder would produce a row that the Play-mandated
        // DELETE /users/me cannot get past.
        String alice = registerAndGetToken();
        String bob = registerAndGetToken();
        UUID bobId = subjectOf(bob);
        String newToken = "fake-linked-max-" + UUID.randomUUID();
        String oldToken = newToken + "-previous";
        UUID aliceRow = seedSubscription(subjectOf(alice), Plan.STANDARD, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(200)), null, null, oldToken);

        assertThat(buy(bob, newToken, "whereis_max_annual").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(supersededByOf(aliceRow)).isNull();
        assertThat(planOf(alice).plan()).isEqualTo(Plan.STANDARD);
        assertThat(planOf(bob).plan()).isEqualTo(Plan.MAX);
        // And the Play-mandated deletion endpoint still works for both, which is the failure the FK
        // exists to prevent.
        assertThat(count("select count(*) from user_subscriptions where user_id = ?", bobId)).isEqualTo(1);
    }

    @Test
    void aDeferredDowngradeKeepsTheOldProductAndReportsWhatHappensNext() {
        // Google keeps the OLD product on the token until the term ends — which is already why
        // resolveLineItem accepts any of our own products rather than only the claimed one — so a
        // plain read cannot tell the user what happens next. lineItem.deferredItemReplacement is
        // the only thing that can, and "your plan changes at some point, we won't say to what" is
        // worse than silence.
        String token = registerAndGetToken();
        String purchaseToken = "fake-deferred-pro-" + UUID.randomUUID();

        assertThat(buy(token, purchaseToken, "whereis_pro_annual").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        PlanStatusResponse status = planOf(token);
        // The CURRENT tier is still PRO: the downgrade has not been paid for and has not happened.
        assertThat(status.plan()).isEqualTo(Plan.PRO);
        assertThat(status.subscription().tier()).isEqualTo(Plan.PRO);
        assertThat(status.subscription().pendingProductId()).isEqualTo("whereis_standard_annual");
        // Resolved at READ time through PlanCatalog#tierOf, never frozen on the row.
        assertThat(status.subscription().pendingTier()).isEqualTo(Plan.STANDARD);
        assertThat(status.subscription().entitling()).isTrue();
    }

    @Test
    void aForegroundResyncDoesNotWipeThePendingDowngradeTheHandlerWrote() {
        // PurchaseSyncer posts the token on EVERY foreground, so this path runs constantly. If
        // pendingProductId were left off the Snapshot, the plan screen's "Pro until 14 March, then
        // Standard" would appear and disappear at random.
        String token = registerAndGetToken();
        String purchaseToken = "fake-deferred-pro-" + UUID.randomUUID();
        buy(token, purchaseToken, "whereis_pro_annual");

        // Two more posts, exactly as three app foregrounds would produce.
        buy(token, purchaseToken, "whereis_pro_annual");
        buy(token, purchaseToken, "whereis_pro_annual");

        assertThat(planOf(token).subscription().pendingProductId())
                .isEqualTo("whereis_standard_annual");
    }

    private ResponseEntity<JsonNode> buy(String token, String purchaseToken, String productId) {
        return post(token, PURCHASES, new PurchaseVerificationRequest(purchaseToken, productId),
                JsonNode.class);
    }

    private PlanStatusResponse planOf(String token) {
        return get(token, "/api/v1/users/me/plan", PlanStatusResponse.class).getBody();
    }

    private UUID supersededByOf(UUID rowId) {
        return jdbc.queryForObject("select superseded_by from user_subscriptions where id = ?",
                UUID.class, rowId);
    }

    private String ledgerOutcome(String messageId) {
        return jdbc.queryForObject("select outcome from play_notifications where message_id = ?",
                String.class, messageId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
