package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.SubscriptionState;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.plan.play.FakePlaySubscriptionsApi;
import az.technest.whereis.plan.play.PlaySubscriptionsApi;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The Pub/Sub push endpoint against real PostgreSQL: authentication, the ledger, the ordering
 * guards, and the ack decision table where it is observable from outside.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlayRtdnIT extends AbstractIntegrationTest {

    @Autowired
    private PlaySubscriptionsApi play;

    @BeforeEach
    void resetTheFakePort() {
        ((FakePlaySubscriptionsApi) play).reset();
    }

    // ---------------------------------------------------------------- authentication

    @Test
    void aRequestWithNoSharedSecretIs401WithAnEmptyBodyAndNoLedgerRow() {
        // No ApiError, no code, no hint about which of the two checks failed. An unauthenticated
        // body is not evidence of anything, so nothing is recorded either.
        String messageId = "it-" + UUID.randomUUID();

        ResponseEntity<String> response = postRtdnRaw(null, RTDN_BEARER,
                envelopeJson(messageId, subscriptionNotification(1000L, 2, "fake-active-pro")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNull();
        assertThat(ledgerRows(messageId)).isZero();
    }

    @Test
    void aRequestWithTheSecretButNoPushTokenIs401() {
        // The two checks are INDEPENDENT and both must pass: a Pub/Sub subscription created without
        // the OIDC option sends no Authorization header at all, which is a configuration mistake
        // that would otherwise be indistinguishable from a working setup.
        String messageId = "it-" + UUID.randomUUID();

        ResponseEntity<String> response = postRtdnRaw(RTDN_SECRET, null,
                envelopeJson(messageId, subscriptionNotification(1000L, 2, "fake-active-pro")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ledgerRows(messageId)).isZero();
    }

    @Test
    void aWrongSharedSecretIs401() {
        assertThat(postRtdnRaw("not-the-secret", RTDN_BEARER,
                envelopeJson("it-" + UUID.randomUUID(), subscriptionNotification(1000L, 2, "fake-active-pro")))
                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anUnauthenticatedRequestWithAnUnparseableBodyIs401AndNothingIsParsed() {
        // The body is read ONLY after both checks pass. With a @RequestBody DTO, Jackson would run
        // during argument resolution and an attacker would get a structured 400 confirming the
        // endpoint exists and what shape it wants — the opposite of a uniform opaque 401.
        ResponseEntity<String> response = postRtdnRaw("not-the-secret", RTDN_BEARER, "{");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void aForeignBearerTokenStillReachesTheControllerRatherThanBeingDecodedByTheJwtFilter() {
        // THE FILTER-CHAIN REGRESSION GUARD. On the main chain,
        // BearerTokenAuthenticationFilter would take Google's RS256 push token out of the
        // Authorization header, hand it to our HS256 decoder, fail, and answer 401 before the
        // controller ran — permitAll would not help, because it governs authorization, not
        // decoding. Every notification would be rejected and the only symptom would be entitlements
        // that quietly stop tracking Google.
        //
        // A syntactically valid, foreign JWT reaching the controller is what proves the @Order(0)
        // chain still exists. It fails check 2 (it is not our fake bearer), so the answer is 401 —
        // but from the CONTROLLER, which is the difference this test is about: the same request
        // with the right bearer, below, is 200.
        String foreignJwt = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJnb29nbGUifQ.c2ln";
        String messageId = "it-" + UUID.randomUUID();

        assertThat(postRtdnRaw(RTDN_SECRET, foreignJwt,
                envelopeJson(messageId, subscriptionNotification(1000L, 2, "fake-active-pro")))
                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(postRtdn(messageId, subscriptionNotification(1000L, 2, "fake-active-pro"))
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- the ledger

    @Test
    void anUndecodableDataFieldIsRecordedAsMalformedAtTheCeilingAndAcked() {
        // THE ROW V10 COULD NOT WRITE, against real PostgreSQL and V11's relaxed NOT NULLs. Without
        // this the insert was a constraint violation, GlobalExceptionHandler answered 409, Pub/Sub
        // nacked, and the same garbage was redelivered for the full 7-day retention while the
        // ledger recorded nothing at all about the one class of message it exists to preserve.
        String messageId = "it-" + UUID.randomUUID();

        ResponseEntity<String> response = postRtdnRaw(RTDN_SECRET, RTDN_BEARER,
                envelopeJson(messageId, "!!!not-base64!!!"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = ledgerRow(messageId);
        assertThat(row.get("outcome")).isEqualTo("MALFORMED");
        assertThat(row.get("processed_at")).isNull();
        assertThat(row.get("attempts")).isEqualTo(10);
        assertThat(row.get("event_time_millis")).isNull();
        assertThat(row.get("package_name")).isNull();
        assertThat(row.get("notification_kind")).isEqualTo("UNKNOWN");
        assertThat((String) row.get("payload")).contains("messageId");
    }

    @Test
    void anEnvelopeWithNoMessageIdIsAckedWithNoLedgerRow() {
        long before = totalLedgerRows();

        assertThat(postRtdnRaw(RTDN_SECRET, RTDN_BEARER, "{\"message\":{\"data\":\"e30=\"}}")
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(totalLedgerRows()).isEqualTo(before);
    }

    @Test
    void aTestNotificationIsRecordedAsIgnoredSoTheConsoleButtonCanBeConfirmed() {
        String messageId = "it-" + UUID.randomUUID();
        String data = base64("{\"version\":\"1.0\",\"packageName\":\"az.technest.whereis\","
                + "\"eventTimeMillis\":\"1000\",\"testNotification\":{\"version\":\"1.0\"}}");

        assertThat(postRtdn(messageId, data).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> row = ledgerRow(messageId);
        assertThat(row.get("notification_kind")).isEqualTo("TEST");
        assertThat(row.get("outcome")).isEqualTo("IGNORED");
        assertThat(row.get("processed_at")).isNotNull();
    }

    @Test
    void anotherAppsPackageIsIgnoredBecauseASharedTopicIsAConsoleProblem() {
        String messageId = "it-" + UUID.randomUUID();
        String data = base64("{\"packageName\":\"com.example.other\",\"eventTimeMillis\":\"1000\","
                + "\"subscriptionNotification\":{\"notificationType\":2,\"purchaseToken\":\"x\"}}");

        assertThat(postRtdn(messageId, data).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ledgerRow(messageId).get("outcome")).isEqualTo("IGNORED");
    }

    // ---------------------------------------------------------------- entitlement effects

    @Test
    void anOnHoldNotificationDropsTheAccountToFreeWithoutDeletingAnything() {
        // The state machine's "Entitled after?" column, measured where it matters: through
        // PlanLimitEnforcer, not by reading the stored state. A test that asserted the state would
        // pass while the wall disagreed with the table.
        //
        // ON_HOLD is the interesting one: not entitling, but the subscription is still live and
        // still auto-renewing, so the account drops to FREE while everything it owns stays exactly
        // where it is — limits gate CREATION and nothing else.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-onhold-pro@" + hashOf(userId);
        UUID rowId = seedRow(userId, purchaseToken, SubscriptionState.ACTIVE, Plan.PRO);
        createSpace(token, "Home", az.technest.whereis.space.SpaceType.HOME);
        assertThat(planOf(token).plan()).isEqualTo(Plan.PRO);

        String messageId = "it-" + UUID.randomUUID();
        assertThat(postRtdn(messageId, subscriptionNotification(nowMillis(), 5, purchaseToken))
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(ledgerRow(messageId).get("outcome")).isEqualTo("APPLIED");
        assertThat(stateOf(rowId)).isEqualTo("ON_HOLD");
        PlanStatusResponse after = planOf(token);
        assertThat(after.plan()).isEqualTo(Plan.FREE);
        // Nothing existing is taken away, and the subscription is STILL reported so the client can
        // show the hold strip and a button that opens Google Play.
        assertThat(after.usage().spaces()).isEqualTo(1L);
        assertThat(after.subscription()).isNotNull();
        assertThat(after.subscription().tier()).isEqualTo(Plan.PRO);
        assertThat(after.subscription().entitling()).isFalse();
    }

    @Test
    void aRenewalRefreshesTheRowFromGoogleAndTheLedgerRecordsItApplied() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-active-pro@" + hashOf(userId);
        UUID rowId = seedRow(userId, purchaseToken, SubscriptionState.ON_HOLD, Plan.STANDARD);

        String messageId = "it-" + UUID.randomUUID();
        assertThat(postRtdn(messageId, subscriptionNotification(nowMillis(), 2, purchaseToken))
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(ledgerRow(messageId).get("outcome")).isEqualTo("APPLIED");
        assertThat(stateOf(rowId)).isEqualTo("ACTIVE");
        // The tier comes from GOOGLE's line item, never from the notification's subscriptionId.
        assertThat(tierOf(rowId)).isEqualTo("PRO");
        assertThat(planOf(token).plan()).isEqualTo(Plan.PRO);
    }

    @Test
    void theSameMessageDeliveredTwiceIsAppliedOnce() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-active-pro@" + hashOf(userId);
        UUID rowId = seedRow(userId, purchaseToken, SubscriptionState.ON_HOLD, Plan.STANDARD);
        String messageId = "it-" + UUID.randomUUID();
        String data = subscriptionNotification(nowMillis(), 2, purchaseToken);

        assertThat(postRtdn(messageId, data).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postRtdn(messageId, data).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(ledgerRows(messageId)).isEqualTo(1);
        // attempts stayed at 1: the redelivery lost the lease and touched nothing.
        assertThat(ledgerRow(messageId).get("attempts")).isEqualTo(1);
        assertThat(stateOf(rowId)).isEqualTo("ACTIVE");
    }

    @Test
    void anOlderEventArrivingAfterANewerOneIsDiscardedAndCannotResurrectTheRow() {
        // Pub/Sub does not guarantee order, so a stale ACTIVE after a fresh EXPIRED is normal
        // traffic, not an anomaly. Mutation check: change `>` to `>=` in the guard and this fails.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-expired-pro@" + hashOf(userId);
        UUID rowId = seedRow(userId, purchaseToken, SubscriptionState.ACTIVE, Plan.PRO);
        long newer = nowMillis();

        postRtdn("it-" + UUID.randomUUID(), subscriptionNotification(newer, 13, purchaseToken));
        assertThat(stateOf(rowId)).isEqualTo("EXPIRED");

        String stale = "it-" + UUID.randomUUID();
        assertThat(postRtdn(stale, subscriptionNotification(newer - 5_000, 2, purchaseToken))
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(ledgerRow(stale).get("outcome")).isEqualTo("DISCARDED_STALE");
        assertThat(stateOf(rowId)).isEqualTo("EXPIRED");
        assertThat(planOf(token).plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void aTokenThisServerHasNeverSeenIsRecordedAsNoLocalRowAndFabricatesNothing() {
        String messageId = "it-" + UUID.randomUUID();

        assertThat(postRtdn(messageId, subscriptionNotification(nowMillis(), 2, "fake-active-pro"))
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(ledgerRow(messageId).get("outcome")).isEqualTo("NO_LOCAL_ROW");
        assertThat(jdbc.queryForObject(
                "select count(*) from user_subscriptions where purchase_token = ?", Long.class,
                "fake-active-pro"))
                .isZero();
    }

    @Test
    void aPlayOutageIsAFiveHundredAndNotTheFiveHundredAndTwoTheGlobalAdviceWouldGive() {
        // PlayApiException is an ApiException with HttpStatus.BAD_GATEWAY. Letting it escape would
        // produce 502 plus an ApiError body from a public endpoint, and — worse — the ledger row
        // would never be finished, so `attempts` would never climb and the message would loop for
        // the full 7-day retention.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-outage-pro@" + hashOf(userId);
        seedRow(userId, purchaseToken, SubscriptionState.ACTIVE, Plan.PRO);
        String messageId = "it-" + UUID.randomUUID();

        ResponseEntity<String> response =
                postRtdn(messageId, subscriptionNotification(nowMillis(), 2, purchaseToken));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNull();
        Map<String, Object> row = ledgerRow(messageId);
        assertThat(row.get("outcome")).isNull();
        assertThat(row.get("processed_at")).isNull();
        assertThat(row.get("processing_error")).isNotNull();
    }

    @Test
    void theSameOutageAtTheCeilingIsAckedAsFailedSoTheMessageStopsLooping() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "fake-outage-pro@" + hashOf(userId);
        seedRow(userId, purchaseToken, SubscriptionState.ACTIVE, Plan.PRO);
        String messageId = "it-" + UUID.randomUUID();
        String data = subscriptionNotification(nowMillis(), 2, purchaseToken);

        ResponseEntity<String> last = null;
        for (int attempt = 0; attempt < 11; attempt++) {
            last = postRtdn(messageId, data);
        }

        assertThat(last).isNotNull();
        assertThat(last.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = ledgerRow(messageId);
        assertThat(row.get("outcome")).isEqualTo("FAILED");
        // FAILED deliberately leaves processed_at NULL: a message that applied nothing must not
        // advance anybody's watermark.
        assertThat(row.get("processed_at")).isNull();
    }

    @Test
    void aTokenGoogleAnswersFourOhFourForIsAckedRatherThanRetriedForever() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        String purchaseToken = "totally-unknown-" + UUID.randomUUID();
        seedRow(userId, purchaseToken, SubscriptionState.ACTIVE, Plan.PRO);
        String messageId = "it-" + UUID.randomUUID();

        ResponseEntity<String> response =
                postRtdn(messageId, subscriptionNotification(nowMillis(), 2, purchaseToken));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ledgerRow(messageId).get("outcome")).isEqualTo("IGNORED");
        // Nothing is revoked on the strength of a Google error.
        assertThat(jdbc.queryForObject(
                "select count(*) from user_subscriptions where purchase_token = ? and voided_at is not null",
                Long.class, purchaseToken))
                .isZero();
    }

    // ---------------------------------------------------------------- helpers

    private PlanStatusResponse planOf(String token) {
        return get(token, "/api/v1/users/me/plan", PlanStatusResponse.class).getBody();
    }

    private UUID seedRow(UUID userId, String purchaseToken) {
        return seedRow(userId, purchaseToken, SubscriptionState.ACTIVE, Plan.PRO);
    }

    private UUID seedRow(UUID userId, String purchaseToken, SubscriptionState state, Plan tier) {
        return seedSubscription(userId, tier, state, Instant.now().plus(Duration.ofDays(365)),
                null, null, purchaseToken);
    }

    private static String hashOf(UUID userId) {
        return az.technest.whereis.plan.play.PlayAccountHash.of(userId);
    }

    private static long nowMillis() {
        return Instant.now().toEpochMilli();
    }

    private int ledgerRows(String messageId) {
        return jdbc.queryForObject("select count(*) from play_notifications where message_id = ?",
                Integer.class, messageId);
    }

    private long totalLedgerRows() {
        return jdbc.queryForObject("select count(*) from play_notifications", Long.class);
    }

    private Map<String, Object> ledgerRow(String messageId) {
        return jdbc.queryForMap("select outcome, processed_at, attempts, event_time_millis,"
                + " package_name, notification_kind, processing_error, payload::text as payload"
                + " from play_notifications where message_id = ?", messageId);
    }

    private String stateOf(UUID rowId) {
        return jdbc.queryForObject("select state from user_subscriptions where id = ?", String.class, rowId);
    }

    private String tierOf(UUID rowId) {
        return jdbc.queryForObject("select tier from user_subscriptions where id = ?", String.class, rowId);
    }
}
