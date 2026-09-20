package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.technest.whereis.auth.dto.RegisterRequest;
import az.technest.whereis.auth.dto.TokenPairResponse;
import az.technest.whereis.common.error.ApiError;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.location.dto.CreateLocationRequest;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.plan.dto.PurchaseVerificationRequest;
import az.technest.whereis.plan.play.DisabledPlaySubscriptionsApi;
import az.technest.whereis.plan.play.PlayBillingNotConfiguredException;
import az.technest.whereis.plan.play.PlaySubscriptionsApi;
import az.technest.whereis.plan.reconcile.PlayCancellationJanitor;
import az.technest.whereis.plan.reconcile.PlayNotificationJanitor;
import az.technest.whereis.plan.reconcile.SubscriptionReconciler;
import az.technest.whereis.plan.reconcile.VoidedPurchaseSweeper;
import az.technest.whereis.plan.rtdn.DisabledPlayPushAuthenticator;
import az.technest.whereis.plan.rtdn.PlayPushAuthenticator;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.CreateSpaceRequest;
import az.technest.whereis.space.dto.SpaceResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <strong>The deployment that is about to happen, booted.</strong> The {@code prod} profile, the
 * real {@code application-prod.yml}, real PostgreSQL — and <strong>not one
 * {@code whereis.play.*} property set</strong>, because the Google Cloud service account and the
 * Pub/Sub topic do not exist yet and cannot be conjured for a test.
 *
 * <p>This is the only class in the suite that forks the Spring context, and the fork IS the point:
 * {@code AbstractIntegrationTest}'s shared registry selects {@code rtdn.verifier=fake}, which the
 * prod profile refuses outright, so this scenario is unreachable from the shared context by
 * construction. It deliberately does not extend that base class for the same reason — inheriting
 * its {@code @DynamicPropertySource} would smuggle in exactly the Play configuration whose absence
 * is under test. It does reuse its singleton containers, so no third container starts.
 *
 * <p>What it must prove, in order of what a deploy depends on:
 * <ol>
 *   <li>the context starts at all, with no Play credentials;</li>
 *   <li>everything non-billing is untouched — the plan endpoint, the limits, the usage numbers and
 *       both 409 walls, which is the entire reason this mode exists;</li>
 *   <li>a purchase attempt fails cleanly and grants nothing;</li>
 *   <li>the push endpoint denies everything and records nothing;</li>
 *   <li>the scheduled billing jobs do not run.</li>
 * </ol>
 *
 * <p><strong>Its own database inside the shared container, and that is not tidiness.</strong>
 * {@code StorageJanitor} is a plain {@code @Scheduled} job with no {@code enabled} flag that fires
 * once at context startup, so a second context sharing one database would put a second drainer on
 * {@code storage_deletion_queue} — and {@code AccountDeletionIT} counts outbox rows in a window it
 * negotiates with the ONE janitor it knows about. Isolating the database removes that race
 * entirely instead of narrowing it, and it is also what lets the assertions below be global
 * ("no subscription row exists at all") rather than scoped to one user, which is a stronger claim.
 * Both containers are still the singletons the rest of the suite uses; nothing new is started.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@Tag("integration")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BillingDisabledProdIT {

    /**
     * The token {@code FakePlaySubscriptionsApi} hands the top tier to. It is the literal that
     * makes {@code provider=fake} a self-service entitlement escalation and therefore banned from
     * production — so it is the right probe for "this mode grants nothing".
     */
    private static final String THE_GUESSABLE_TOKEN = "fake-active-max";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Its own database in the shared server; Flyway builds the schema from V1 on first boot. */
    private static final String DATABASE = "whereis_billing_off_it";

    static {
        try (Connection connection = AbstractIntegrationTest.POSTGRES.createConnection("");
                Statement statement = connection.createStatement()) {
            statement.execute("create database " + DATABASE);
        } catch (SQLException alreadyThere) {
            // A re-run inside the same container. Nothing to do: Flyway is idempotent.
        }
    }

    @DynamicPropertySource
    static void prodEnvironmentWithoutAnyPlayConfiguration(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", BillingDisabledProdIT::jdbcUrl);
        registry.add("spring.datasource.username", AbstractIntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", AbstractIntegrationTest.POSTGRES::getPassword);
        registry.add("minio.endpoint", AbstractIntegrationTest.MINIO::getS3URL);
        registry.add("minio.external-endpoint", AbstractIntegrationTest.MINIO::getS3URL);
        registry.add("minio.access-key", AbstractIntegrationTest.MINIO::getUserName);
        registry.add("minio.secret-key", AbstractIntegrationTest.MINIO::getPassword);

        // application-test.yml is NOT active here, so the secret has to come from somewhere.
        registry.add("security.jwt.secret", () -> "prod-profile-integration-secret-0123456789");

        // The seven values application-prod.yml declares with no default. They render the two public
        // legal pages and LegalPages refuses to boot on a leftover marker, so they are genuinely
        // required of any prod deployment — unlike the Play values, which are the subject here.
        registry.add("whereis.legal.support-email", () -> "support@example.com");
        registry.add("whereis.legal.legal-entity", () -> "whereis IT");
        registry.add("whereis.legal.legal-address", () -> "Baku");
        registry.add("whereis.legal.effective-date", () -> "2026-09-20");
        registry.add("whereis.legal.backup-retention-days", () -> "14");
        registry.add("whereis.legal.cancellation-retry-days", () -> "7");
        // PlayNotificationJanitor reads this one as well, and refuses to boot at 7 or below — so a
        // prod context starting at all is also the proof that the floor accepts the shipped value.
        registry.add("whereis.legal.billing-log-retention-days", () -> "30");

        // AND NOTHING ELSE. No whereis.play.provider, no rtdn verifier, no shared secret, no
        // audience, no service-account email, no service-account key. Adding any of them here
        // would destroy the only thing this class proves.
    }

    private static String jdbcUrl() {
        String shared = AbstractIntegrationTest.POSTGRES.getJdbcUrl();
        return shared.substring(0, shared.lastIndexOf('/') + 1) + DATABASE;
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlaySubscriptionsApi play;

    @Autowired
    private PlayPushAuthenticator pushAuthenticator;

    @Autowired
    private SubscriptionReconciler reconciler;

    @Autowired
    private VoidedPurchaseSweeper voidedSweeper;

    @Autowired
    private PlayCancellationJanitor cancellationJanitor;

    @Autowired
    private PlayNotificationJanitor notificationJanitor;

    // ---------------------------------------------------------------- 1. it starts at all

    @Test
    void theProdProfileStartsWithNoPlayCredentialsAndSelectsTheDisabledImplementations() {
        // The two beans that would otherwise have demanded a Google key and a Pub/Sub audience.
        assertThat(play).isInstanceOf(DisabledPlaySubscriptionsApi.class);
        assertThat(pushAuthenticator).isInstanceOf(DisabledPlayPushAuthenticator.class);
    }

    @Test
    void theReadinessProbeTheDeploymentHealthcheckUsesAnswers() {
        // docker-compose.prod.yml's healthcheck is exactly this URL; a context that starts but
        // never reports ready would still fail the deploy.
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // -------------------------------------------- 2. everything non-billing is untouched

    /**
     * The reason this mode exists. A free-tier account that has nothing to do with billing gets the
     * complete, correct plan answer from a deployment with no billing configuration at all.
     */
    @Test
    void thePlanEndpointAnswersInFullForAFreeAccount() {
        String token = registerAndGetToken();

        PlanStatusResponse plan = getPlan(token);

        assertThat(plan.plan()).isEqualTo(Plan.FREE);
        assertThat(plan.limits()).isNotNull();
        assertThat(plan.limits().spaces()).isEqualTo(1);
        assertThat(plan.limits().items()).isEqualTo(100);
        assertThat(plan.usage().spaces()).isZero();
        assertThat(plan.usage().activeItems()).isZero();
        assertThat(plan.subscription()).isNull();
    }

    @Test
    void usageTracksRealRowsRatherThanBeingStubbedOutWithBillingOff() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home");
        LocationResponse shelf = createLocation(token, home.id(), "Shelf");
        createItem(token, shelf.id(), "Passport");

        PlanStatusResponse plan = getPlan(token);

        assertThat(plan.usage().spaces()).isEqualTo(1);
        assertThat(plan.usage().activeItems()).isEqualTo(1);
        assertThat(plan.plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void theSpaceWallStillRefusesASecondSpaceWith409PlanLimitReached() {
        String token = registerAndGetToken();
        createSpace(token, "Home");

        ResponseEntity<ApiError> refused = rest.exchange("/api/v1/spaces", HttpMethod.POST,
                new HttpEntity<>(new CreateSpaceRequest("Office", null, SpaceType.OFFICE), bearer(token)),
                ApiError.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).isNotNull();
        assertThat(refused.getBody().code()).isEqualTo(ErrorCode.PLAN_LIMIT_REACHED.name());
    }

    @Test
    void theItemWallStillRefusesThe101stItemWith409PlanLimitReached() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        SpaceResponse home = createSpace(token, "Home");
        LocationResponse shelf = createLocation(token, home.id(), "Shelf");
        seedActiveItems(userId, shelf.id(), 100);

        ResponseEntity<ApiError> refused = rest.exchange("/api/v1/items", HttpMethod.POST,
                new HttpEntity<>(new CreateItemRequest("One too many", null, null, shelf.id()),
                        bearer(token)),
                ApiError.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).isNotNull();
        assertThat(refused.getBody().code()).isEqualTo(ErrorCode.PLAN_LIMIT_REACHED.name());
        assertThat(getPlan(token).usage().activeItems()).isEqualTo(100);
    }

    /**
     * The ladder is pure configuration, so it is still readable with billing off. That matters to
     * the client: the upgrade screen renders from {@code GET /plans}, and an empty answer would
     * look like a broken deployment rather than a switched-off feature.
     */
    @Test
    void theTierLadderIsStillReadableBecauseItIsConfigurationRatherThanBilling() {
        String token = registerAndGetToken();

        ResponseEntity<String> ladder = rest.exchange("/api/v1/plans", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class);

        assertThat(ladder.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ladder.getBody()).contains("FREE").contains("STANDARD").contains("PRO")
                .contains("MAX").doesNotContain("UNLIMITED");
    }

    // ------------------------------------------- 3. a purchase fails cleanly and grants nothing

    /**
     * <strong>The chosen answer: 501 {@code PLAY_BILLING_NOT_CONFIGURED}.</strong> Not 200 (that
     * body IS a grant), not 400/404 (both tell the client to drop a token it may have paid for),
     * not 502/503 (both mean "retry", and no amount of retrying creates a Google Cloud project).
     *
     * <p>The probe is the fake provider's guessable literal, the exact token that makes
     * {@code provider=fake} unsafe in production.
     */
    @Test
    void aPurchaseAttemptIsRefusedWith501AndGrantsNothing() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);

        ResponseEntity<ApiError> refused = rest.exchange("/api/v1/users/me/plan/purchases",
                HttpMethod.POST,
                new HttpEntity<>(new PurchaseVerificationRequest(THE_GUESSABLE_TOKEN, "whereis_max_annual"),
                        bearer(token)),
                ApiError.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThat(refused.getBody()).isNotNull();
        assertThat(refused.getBody().code()).isEqualTo(ErrorCode.PLAY_BILLING_NOT_CONFIGURED.name());
        // The client has to know the token is still worth keeping.
        assertThat(refused.getBody().message()).contains("Keep the purchase token");

        assertNothingWasGrantedTo(userId, token);
    }

    /**
     * The same refusal for a token nobody could mistake for valid, and for a product id this server
     * does not offer — the mode answers one thing, so a client cannot end up in a
     * "drop the token" branch because of an unrelated field.
     */
    @Test
    void everyPurchaseShapeGetsTheSameRefusalRatherThanADropTheTokenAnswer() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);

        for (PurchaseVerificationRequest attempt : java.util.List.of(
                new PurchaseVerificationRequest("a-token-google-never-issued", "whereis_pro_annual"),
                new PurchaseVerificationRequest(THE_GUESSABLE_TOKEN, "whereis_not_a_product"))) {
            ResponseEntity<ApiError> refused = rest.exchange("/api/v1/users/me/plan/purchases",
                    HttpMethod.POST, new HttpEntity<>(attempt, bearer(token)), ApiError.class);

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
            assertThat(refused.getBody()).isNotNull();
            assertThat(refused.getBody().code())
                    .isEqualTo(ErrorCode.PLAY_BILLING_NOT_CONFIGURED.name());
        }

        assertNothingWasGrantedTo(userId, token);
    }

    // ------------------------------------------------ 4. the push endpoint denies everything

    /**
     * Every authentication shape the endpoint distinguishes, all 401 with a zero-length body and no
     * ledger row. The last case is the important one: a well-formed Pub/Sub envelope carrying a
     * {@code SUBSCRIPTION_PURCHASED} notification — the message that would otherwise create an
     * entitlement — is refused like the rest.
     */
    @Test
    void thePushEndpointDeniesEveryCallerAndWritesNoLedgerRow() {
        String messageId = "billing-off-" + UUID.randomUUID();
        String body = envelope(messageId);

        for (String[] auth : new String[][]{
                {null, null},
                {"", ""},
                {"any-key", "any-bearer"},
                {"any-key", "eyJhbGciOiJSUzI1NiJ9.eyJhdWQiOiJ3aGVyZWlzLXJ0ZG4ifQ.c2ln"}}) {
            ResponseEntity<String> denied = postRtdn(auth[0], auth[1], body);

            assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(denied.getBody()).isNull();
        }

        Integer rows = jdbc.queryForObject("select count(*) from play_notifications", Integer.class);
        assertThat(rows).as("a denied push must leave no ledger row: an unauthenticated body is "
                + "not evidence of anything").isZero();
    }

    /**
     * <strong>Check 2 denies on its own, not merely because check 1's secret is blank.</strong>
     * The endpoint's own unit-level proof is {@code RtdnConfigurationTest}; what this pins is that
     * the bean actually wired into the prod context is the deny-all one, so the two facts compose
     * into "no input reaches the handler".
     */
    @Test
    void thePushVerifierItselfRejectsEvenAGoogleShapedToken() {
        assertThat(pushAuthenticator.isGenuine("eyJhbGciOiJSUzI1NiJ9.eyJhdWQiOiJ3aGVyZWlzLXJ0ZG4ifQ.c2ln"))
                .isFalse();
        assertThat(pushAuthenticator.isGenuine(null)).isFalse();
    }

    // ------------------------------------------------- 5. the scheduled jobs do not run

    /**
     * <strong>The gate, and the proof that it is the gate.</strong> {@code sweep()} — the
     * {@code @Scheduled} entry point — is a silent no-op, while {@code runOnce()} on the same bean
     * throws {@link PlayBillingNotConfiguredException}. Together those say two things one of them
     * alone could not: the job really would fail on every tick, and it is the billing check rather
     * than an empty work queue that keeps the log clean.
     */
    @Test
    void theThreeScheduledBillingJobsAreNoOpsAndWouldOtherwiseRefuse() {
        reconciler.sweep();
        voidedSweeper.sweep();
        cancellationJanitor.sweep();

        assertThatThrownBy(() -> voidedSweeper.runOnce())
                .isInstanceOf(PlayBillingNotConfiguredException.class);
    }

    /**
     * <strong>The FOURTH scheduled component is deliberately not gated the same way.</strong> The
     * three above call the Play API, so under {@code provider=disabled} a tick would throw on its
     * first row forever. The ledger retention sweep makes no Play call — it is one {@code DELETE} —
     * and a deployment that switches billing off must still purge the ledger it accumulated while
     * billing was on. Gating it on the provider would turn "billing is off" into "the retention
     * promise on two public pages stopped being kept", silently. So it runs here, and it must.
     */
    @Test
    void theLedgerRetentionSweepStillRunsWithBillingDisabledBecauseItCallsNothing() {
        assertThatCode(() -> notificationJanitor.sweep()).doesNotThrowAnyException();
        assertThatCode(() -> notificationJanitor.runOnce()).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------- helpers

    /**
     * "Grants nothing" stated as the three places a grant could possibly appear: no subscription
     * row ANYWHERE in this database — not merely none for this user — no change to the
     * operator-grant column, and the plan endpoint still reporting FREE.
     */
    private void assertNothingWasGrantedTo(UUID userId, String accessToken) {
        assertThat(jdbc.queryForObject("select count(*) from user_subscriptions", Integer.class))
                .as("no purchase may create a subscription row while billing is not configured")
                .isZero();
        assertThat(jdbc.queryForObject("select plan from users where id = ?", String.class, userId))
                .isEqualTo(Plan.FREE.name());
        assertThat(getPlan(accessToken).plan()).isEqualTo(Plan.FREE);
    }

    private PlanStatusResponse getPlan(String accessToken) {
        ResponseEntity<PlanStatusResponse> response = rest.exchange("/api/v1/users/me/plan",
                HttpMethod.GET, new HttpEntity<>(bearer(accessToken)), PlanStatusResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<String> postRtdn(String key, String bearer, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        String url = key == null ? "/play/rtdn" : "/play/rtdn?key=" + key;
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** A SUBSCRIPTION_PURCHASED push — the notification that would create an entitlement. */
    private static String envelope(String messageId) {
        String notification = Base64.getEncoder().encodeToString(
                ("{\"version\":\"1.0\",\"packageName\":\"az.technest.whereis\","
                        + "\"eventTimeMillis\":\"1758000000000\","
                        + "\"subscriptionNotification\":{\"version\":\"1.0\",\"notificationType\":4,"
                        + "\"purchaseToken\":\"" + THE_GUESSABLE_TOKEN + "\","
                        + "\"subscriptionId\":\"whereis_max_annual\"}}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "{\"message\":{\"messageId\":\"" + messageId + "\",\"data\":\"" + notification
                + "\",\"publishTime\":\"2026-09-20T10:00:00Z\"},"
                + "\"subscription\":\"projects/p/subscriptions/s\"}";
    }

    private String registerAndGetToken() {
        ResponseEntity<TokenPairResponse> response = rest.postForEntity("/api/v1/auth/register",
                new RegisterRequest("prod-" + UUID.randomUUID() + "@example.com", "password123",
                        "Prod", "Tester"),
                TokenPairResponse.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody().accessToken();
    }

    private UUID subjectOf(String accessToken) {
        try {
            String payload = accessToken.split("\\.")[1];
            return UUID.fromString(
                    JSON.readTree(Base64.getUrlDecoder().decode(payload)).get("sub").asText());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SpaceResponse createSpace(String accessToken, String name) {
        ResponseEntity<SpaceResponse> response = rest.exchange("/api/v1/spaces", HttpMethod.POST,
                new HttpEntity<>(new CreateSpaceRequest(name, null, SpaceType.HOME), bearer(accessToken)),
                SpaceResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private LocationResponse createLocation(String accessToken, UUID spaceId, String name) {
        ResponseEntity<LocationResponse> response = rest.exchange(
                "/api/v1/spaces/" + spaceId + "/locations", HttpMethod.POST,
                new HttpEntity<>(new CreateLocationRequest(name, null, LocationType.SHELF, null),
                        bearer(accessToken)),
                LocationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void createItem(String accessToken, UUID locationId, String name) {
        ResponseEntity<String> response = rest.exchange("/api/v1/items", HttpMethod.POST,
                new HttpEntity<>(new CreateItemRequest(name, null, null, locationId), bearer(accessToken)),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** One statement, the same trick {@code AbstractIntegrationTest} uses to reach the item wall. */
    private void seedActiveItems(UUID userId, UUID locationId, int count) {
        jdbc.update("""
                insert into items (id, user_id, current_location_id, name, normalized_name, archived)
                select gen_random_uuid(), ?, ?, 'Seed ' || g, 'seed ' || g, false
                from generate_series(1, ?) g
                """, userId, locationId, count);
    }

    private HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
