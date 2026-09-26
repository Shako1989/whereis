package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.dto.PurchaseVerificationRequest;
import az.technest.whereis.plan.play.FakePlaySubscriptionsApi;
import az.technest.whereis.plan.play.PlayAccountHash;
import az.technest.whereis.plan.play.PlaySubscriptionsApi;
import az.technest.whereis.space.SpaceType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/**
 * {@code POST /api/v1/users/me/plan/purchases} against a real database and the deterministic Play
 * fake — which is the DEFAULT provider, so there is no {@code @TestPropertySource} here and the
 * shared Spring context is not forked.
 *
 * <p>The point of these is the same as {@code PlanStatusIT}'s: agreement. A purchase that verifies
 * 200 must actually raise the wall, and a purchase that is refused must leave the account exactly
 * where it was.
 */
class PlanPurchaseIT extends AbstractIntegrationTest {

    private static final String PURCHASES = "/api/v1/users/me/plan/purchases";
    private static final String PLAN = "/api/v1/users/me/plan";
    private static final String SPACES = "/api/v1/spaces";
    private static final String STANDARD = "whereis_standard_annual";
    private static final String PRO = "whereis_pro_annual";

    @Autowired
    private PlaySubscriptionsApi play;

    @BeforeEach
    void forgetEarlierAcknowledgements() {
        ((FakePlaySubscriptionsApi) play).reset();
    }

    /**
     * A purchase token unique to this test. The fake keys off the {@code fake-<kind>-<tier>}
     * prefix, so a random suffix is still the same purchase shape — and it has to be unique,
     * because {@code purchase_token} is globally unique and these tests share one database.
     */
    private static String tokenFor(String shape) {
        return "fake-" + shape + "-" + UUID.randomUUID();
    }

    private ResponseEntity<JsonNode> buy(String token, String purchaseToken, String productId) {
        return post(token, PURCHASES, new PurchaseVerificationRequest(purchaseToken, productId),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> createSpaceRaw(String token, String name, SpaceType type) {
        return post(token, SPACES,
                new az.technest.whereis.space.dto.CreateSpaceRequest(name, null, type), JsonNode.class);
    }

    private int rowsFor(UUID userId) {
        return jdbc.queryForObject("select count(*) from user_subscriptions where user_id = ?",
                Integer.class, userId);
    }

    private int rowsForToken(String purchaseToken) {
        return jdbc.queryForObject("select count(*) from user_subscriptions where purchase_token = ?",
                Integer.class, purchaseToken);
    }

    // ------------------------------------------------------------------------- the happy path

    @Test
    void aStandardPurchaseRaisesTheWallAndTheSecondSpaceNowSucceeds() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        createSpace(token, "Home", SpaceType.HOME);
        // Free is one space: the second is refused before the purchase.
        assertThat(createSpaceRaw(token, "Office", SpaceType.OFFICE).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        String standardToken = tokenFor("active-standard");
        ResponseEntity<JsonNode> bought = buy(token, standardToken, STANDARD);

        assertThat(bought.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The 200 body IS the plan status, so the purchase result and the plan screen cannot
        // disagree — no second GET is needed to learn what was bought.
        assertThat(bought.getBody().get("plan").asText()).isEqualTo("STANDARD");
        assertThat(bought.getBody().get("limits").get("spaces").asInt()).isEqualTo(2);
        assertThat(bought.getBody().get("source").asText()).isEqualTo("SUBSCRIPTION");
        assertThat(bought.getBody().get("subscription").get("acknowledged").asBoolean()).isTrue();

        assertThat(createSpaceRaw(token, "Office", SpaceType.OFFICE).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(rowsFor(userId)).isEqualTo(1);
        assertThat(((FakePlaySubscriptionsApi) play).acknowledgedTokens()).contains(standardToken);
    }

    @Test
    void theStoredRowCarriesGooglesAnswerAndNeverTheHighWaterMark() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);

        buy(token, tokenFor("test-pro"), PRO);

        // last_event_time stays NULL: verifying applies no RTDN, and a seeded watermark would make
        // the next wave's handler discard every notification already in flight for this purchase.
        assertThat(jdbc.queryForObject(
                "select last_event_time from user_subscriptions where user_id = ?", Long.class, userId))
                .isNull();
        assertThat(jdbc.queryForObject(
                "select test_purchase from user_subscriptions where user_id = ?", Boolean.class, userId))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "select tier from user_subscriptions where user_id = ?", String.class, userId))
                .isEqualTo("PRO");
    }

    @Test
    void aPromoRedemptionIsRecordedAsPromoCode() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);

        assertThat(buy(token, tokenFor("trial-pro"), PRO).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(jdbc.queryForObject(
                "select provenance from user_subscriptions where user_id = ?", String.class, userId))
                .isEqualTo("PROMO_CODE");
    }

    @Test
    void aCanceledPurchaseStillEntitlesBecauseThePaidTermIsNotOver() {
        String token = registerAndGetToken();

        ResponseEntity<JsonNode> bought = buy(token, tokenFor("canceled-max"), "whereis_max_annual");

        assertThat(bought.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bought.getBody().get("plan").asText()).isEqualTo("MAX");
        assertThat(bought.getBody().get("subscription").get("state").asText()).isEqualTo("CANCELED");
        // MAX: the top of the ladder, every allowance finite.
        assertThat(bought.getBody().get("limits").get("spaces").asInt()).isEqualTo(5);
        assertThat(bought.getBody().get("limits").get("items").asInt()).isEqualTo(220);
    }

    @Test
    void anAlreadyAcknowledgedPurchaseIsNotAcknowledgedAgain() {
        String token = registerAndGetToken();

        String ackedToken = tokenFor("acked-pro");

        assertThat(buy(token, ackedToken, PRO).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(((FakePlaySubscriptionsApi) play).acknowledgedTokens()).doesNotContain(ackedToken);
    }

    @Test
    void anAcknowledgementFailureStillEntitlesAndSaysSoOnTheWire() {
        String token = registerAndGetToken();

        ResponseEntity<JsonNode> bought = buy(token, tokenFor("ackfails-pro"), PRO);

        assertThat(bought.getStatusCode()).isEqualTo(HttpStatus.OK);
        // acknowledged=false is the client's instruction to re-post on the next foreground rather
        // than wait 24 hours — the interim substitute for the reconciler, which is next wave.
        assertThat(bought.getBody().get("subscription").get("acknowledged").asBoolean()).isFalse();
        assertThat(bought.getBody().get("plan").asText()).isEqualTo("PRO");
    }

    // ------------------------------------------------------------------------ idempotency

    @Test
    void replayingTheSameTokenIsANoOpWithExactlyOneRow() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);

        String replayed = tokenFor("active-pro");

        assertThat(buy(token, replayed, PRO).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(buy(token, replayed, PRO).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(buy(token, replayed, PRO).getStatusCode()).isEqualTo(HttpStatus.OK);

        // The unique constraint IS the idempotency key; the client replays on every foreground.
        assertThat(rowsFor(userId)).isEqualTo(1);
    }

    @Test
    void twoAccountsRacingTheSameTokenProduceOneRowOneSuccessAndOne409() throws Exception {
        String first = registerAndGetToken();
        String second = registerAndGetToken();
        String contested = tokenFor("active-pro");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<JsonNode>> a = pool.submit(() -> {
                start.await();
                return buy(first, contested, PRO);
            });
            Future<ResponseEntity<JsonNode>> b = pool.submit(() -> {
                start.await();
                return buy(second, contested, PRO);
            });
            start.countDown();
            ResponseEntity<JsonNode> responseA = a.get(30, TimeUnit.SECONDS);
            ResponseEntity<JsonNode> responseB = b.get(30, TimeUnit.SECONDS);
            HttpStatusCode statusA = responseA.getStatusCode();
            HttpStatusCode statusB = responseB.getStatusCode();

            // Exactly one row, and the loser is told the truth rather than being answered 200 with
            // a FREE body it cannot explain. The insert race is repaired in a FRESH transaction
            // that re-runs the ownership check — the first one ran before the Google round trip.
            assertThat(rowsForToken(contested)).isEqualTo(1);
            assertThat(java.util.List.of(statusA, statusB))
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void asecondAccountPostingAKnownTokenIsRefusedAndTheFirstAccountKeepsItsTier() {
        String first = registerAndGetToken();
        String second = registerAndGetToken();
        String bought = tokenFor("active-pro");
        assertThat(buy(first, bought, PRO).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> stolen = buy(second, bought, PRO);

        assertThat(stolen.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stolen.getBody().get("code").asText()).isEqualTo("PLAN_PURCHASE_NOT_OWNED");
        assertThat(rowsFor(subjectOf(second))).isZero();
        assertThat(get(first, PLAN, JsonNode.class).getBody().get("plan").asText()).isEqualTo("PRO");
        assertThat(get(second, PLAN, JsonNode.class).getBody().get("plan").asText()).isEqualTo("FREE");
    }

    @Test
    void aPurchaseWhoseObfuscatedAccountIdNamesAnotherUserIsRefusedWithNothingWritten() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String foreignHash = PlayAccountHash.of(UUID.randomUUID());

        ResponseEntity<JsonNode> refused = buy(token, tokenFor("active-pro") + "@" + foreignHash, PRO);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("PLAN_PURCHASE_NOT_OWNED");
        assertThat(rowsFor(userId)).isZero();
    }

    @Test
    void aPurchaseWhoseObfuscatedAccountIdNamesTheCallerIsAccepted() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);

        ResponseEntity<JsonNode> bought =
                buy(token, tokenFor("active-pro") + "@" + PlayAccountHash.of(userId), PRO);

        assertThat(bought.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bought.getBody().get("plan").asText()).isEqualTo("PRO");
    }

    // ------------------------------------------------------------------------ refusals

    @Test
    void anExpiredTokenLeavesTheRowAndTheCallerFree() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);

        ResponseEntity<JsonNode> refused = buy(token, tokenFor("expired-pro"), PRO);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("PLAY_PURCHASE_NOT_ACTIVE");
        // The row is kept deliberately: the next wave's RECOVERED notification needs something to
        // update, and the caller is still FREE because the row does not entitle.
        assertThat(rowsFor(userId)).isEqualTo(1);
        assertThat(get(token, PLAN, JsonNode.class).getBody().get("plan").asText()).isEqualTo("FREE");
    }

    @Test
    void anOutageLeavesZeroRowsAndIsRetryable() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);

        ResponseEntity<JsonNode> refused = buy(token, tokenFor("outage-pro"), PRO);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("PLAY_UNAVAILABLE");
        assertThat(rowsFor(userId)).isZero();
    }

    @Test
    void aTokenGoogleDoesNotKnowIsDropped() {
        String token = registerAndGetToken();

        ResponseEntity<JsonNode> refused = buy(token, "a-token-google-never-issued-"
                + UUID.randomUUID(), PRO);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("PLAY_PURCHASE_INVALID");
        assertThat(rowsFor(subjectOf(token))).isZero();
    }

    @Test
    void anUnconfiguredProductIdIsRefusedBeforeGoogleIsAsked() {
        String token = registerAndGetToken();

        ResponseEntity<JsonNode> refused = buy(token, tokenFor("active-pro"), "whereis_platinum_annual");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("PLAY_PRODUCT_UNKNOWN");
    }

    @Test
    void aPurchaseOfferingNoneOfOurProductsIsAMismatch() {
        String token = registerAndGetToken();

        ResponseEntity<JsonNode> refused = buy(token, tokenFor("foreignproduct-pro"), PRO);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("PLAY_PRODUCT_MISMATCH");
        assertThat(rowsFor(subjectOf(token))).isZero();
    }

    @Test
    void aMalformedBodyIsAValidationErrorAndAnonymousIsUnauthorized() {
        String token = registerAndGetToken();

        assertThat(post(token, PURCHASES, new PurchaseVerificationRequest("", PRO), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(token, PURCHASES,
                new PurchaseVerificationRequest(tokenFor("active-pro"), "NOT A PRODUCT"), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.postForEntity(PURCHASES,
                new PurchaseVerificationRequest(tokenFor("active-pro"), PRO), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anOperatorGrantIsNeverOverwrittenByAPurchase() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        grantTier(userId, Plan.UNLIMITED);

        assertThat(buy(token, tokenFor("active-standard"), STANDARD).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // users.plan is untouched by billing, and max() keeps the grant on top — the whole reason
        // the entitlement is not an override.
        assertThat(jdbc.queryForObject("select plan from users where id = ?", String.class, userId))
                .isEqualTo("UNLIMITED");
        JsonNode plan = get(token, PLAN, JsonNode.class).getBody();
        assertThat(plan.get("plan").asText()).isEqualTo("UNLIMITED");
        assertThat(plan.get("source").asText()).isEqualTo("GRANT");
        // The paid subscription is still reported, so it stays manageable.
        assertThat(plan.get("subscription").get("tier").asText()).isEqualTo("STANDARD");
    }
}
