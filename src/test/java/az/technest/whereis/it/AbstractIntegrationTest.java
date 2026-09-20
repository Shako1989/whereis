package az.technest.whereis.it;

import az.technest.whereis.auth.dto.RegisterRequest;
import az.technest.whereis.auth.dto.TokenPairResponse;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.SubscriptionState;
import az.technest.whereis.location.dto.CreateLocationRequest;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.CreateSpaceRequest;
import az.technest.whereis.space.dto.SpaceResponse;
import az.technest.whereis.storage.dto.ItemFileResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton-container base: PostgreSQL + MinIO start once per JVM and are shared by
 * every IT class (identical property sets keep a single Spring context). Requires Docker;
 * excluded from the plain `test` task via the "integration" tag.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("integration")
public abstract class AbstractIntegrationTest {

    // Image names are overridable for networks where Docker Hub is unreachable
    // (corporate proxy / registry mirror): -Dit.postgres.image=... -Dit.minio.image=...
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(System.getProperty("it.postgres.image", "postgres:16-alpine"))
                    .asCompatibleSubstituteFor("postgres"));
    // Wait for READINESS, not liveness: /minio/health/live answers before the S3 API accepts
    // requests, and on a loaded machine the first uploads of the run have failed with 502 in
    // that gap. /minio/health/ready flips only once the object API is serving.
    static final MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse(System.getProperty("it.minio.image", "minio/minio:RELEASE.2023-09-04T19-57-37Z"))
                    .asCompatibleSubstituteFor("minio/minio"))
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000)
                    .withStartupTimeout(Duration.ofSeconds(90)));

    static {
        POSTGRES.start();
        MINIO.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("minio.endpoint", MINIO::getS3URL);
        registry.add("minio.external-endpoint", MINIO::getS3URL);
        registry.add("minio.access-key", MINIO::getUserName);
        registry.add("minio.secret-key", MINIO::getPassword);

        // THE RTDN AND SCHEDULER SETTINGS LIVE HERE, on the SHARED registry, and not on a
        // per-class @TestPropertySource. §8 records why: the ITs deliberately run with the REAL
        // production limits because a @TestPropertySource forks the shared Testcontainers context,
        // and three more forks is exactly the cost the suite was designed to avoid. Every RTDN test
        // drives its variation through the REQUEST, never through the context.
        //
        // The push endpoint needs a configured secret and the fake verifier or every request is
        // 401 — blank REJECTS by design, which is the whole point of check 1 and check 2.
        registry.add("whereis.play.rtdn.verifier", () -> "fake");
        registry.add("whereis.play.rtdn.shared-secret", () -> RTDN_SECRET);
        registry.add("whereis.play.rtdn.fake-bearer", () -> RTDN_BEARER);
        registry.add("whereis.play.rtdn.audience", () -> "whereis-rtdn-test");

        // @EnableScheduling is global, so every one of these would otherwise fire inside the
        // shared context and drain or repair whatever a test had just set up — making
        // AccountDeletionIT and the new billing ITs order-dependent. Each test drives its component
        // by calling sweep() directly, which is also the only way to assert a single pass.
        registry.add("whereis.play.reconcile.enabled", () -> "false");
        registry.add("whereis.play.voided-sweep.enabled", () -> "false");
        registry.add("whereis.play.cancellation.enabled", () -> "false");
        // The ledger retention sweep joins them. Its cutoff is 30 days back so it could not touch a
        // row a test just inserted, but AccountDeletionIT ages one row past the window ON PURPOSE
        // and then asserts the effect of ONE pass — a background tick could do it first.
        registry.add("whereis.play.notification-retention.enabled", () -> "false");
    }

    /** Check 1: the {@code ?key=} of the registered push URL. */
    protected static final String RTDN_SECRET = "integration-test-shared-secret";

    /** Check 2: the literal the fake push authenticator accepts. */
    protected static final String RTDN_BEARER = "integration-test-push-token";

    /** The password every {@link #register()} call uses — needed by tests that re-authenticate. */
    protected static final String PASSWORD = "password123";

    /** Smallest byte sequence that passes the JPEG magic-byte check. */
    protected static final byte[] JPEG_BYTES =
            {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 10, 20, 30, 40, 50, 60, 70, 80};

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    protected TestRestTemplate rest;

    /** Row-count and jsonb assertions straight from the database. Same context: no fork. */
    @Autowired
    protected JdbcTemplate jdbc;

    /** Registers a fresh user with a random e-mail and returns the whole token pair. */
    protected TokenPairResponse register() {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<TokenPairResponse> response = rest.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, PASSWORD, "Test", "User"), TokenPairResponse.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Registration failed: " + response.getStatusCode());
        }
        return response.getBody();
    }

    protected String registerAndGetToken() {
        return register().accessToken();
    }

    /** The JWT subject, i.e. the user id the backend derives ownership from. Signature is not checked here. */
    protected UUID subjectOf(String accessToken) {
        String payload = accessToken.split("\\.")[1];
        try {
            return UUID.fromString(JSON.readTree(Base64.getUrlDecoder().decode(payload)).get("sub").asText());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Grants the account unlimited use, exactly as an operator does after a deploy
     * (deploy/README.md) — one UPDATE on {@code users.plan}, no endpoint and no application code.
     *
     * <p>Needed by any scenario that exceeds the free tier: with {@code whereis.limits.free.spaces}
     * = 1 a FREE account cannot own a second space, so the tests that legitimately span two spaces
     * (the MVP journey's cross-space move among them) are tests of a granted account. The ITs
     * deliberately run with the REAL production limits rather than inflated test ones.
     */
    protected void grantUnlimited(UUID userId) {
        int updated = jdbc.update("update users set plan = 'UNLIMITED' where id = ?", userId);
        if (updated != 1) {
            throw new IllegalStateException("Expected to grant exactly one account, updated " + updated);
        }
    }

    /**
     * Grants any tier on the ladder by hand, exactly as an operator does — the STANDARD/PRO/MAX
     * form of {@link #grantUnlimited(UUID)}, which V10 made possible by widening
     * {@code ck_users_plan}.
     *
     * <p>Remember what this column is: the operator GRANT, never billing state. The effective tier
     * is the higher of this and the account's best entitling subscription.
     */
    protected void grantTier(UUID userId, Plan tier) {
        int updated = jdbc.update("update users set plan = ? where id = ?", tier.name(), userId);
        if (updated != 1) {
            throw new IllegalStateException("Expected to grant exactly one account, updated " + updated);
        }
    }

    /**
     * Inserts a {@code user_subscriptions} row directly, so a test can stand an account on a paid
     * tier without driving a purchase through the endpoint. Returns the row id.
     *
     * <p>Raw SQL on purpose: it is the only way to produce the rows the RTDN handler will later
     * create (voided, superseded, paused) while that handler does not exist, and the entitlement
     * query has to be exercised against every one of them.
     *
     * <p>{@code last_event_time} is left NULL, which is also what the verify endpoint does — see
     * the column comment in V10.
     */
    protected UUID seedSubscription(UUID userId, Plan tier, SubscriptionState state, Instant entitledUntil) {
        return seedSubscription(userId, tier, state, entitledUntil, null, null,
                "seed-" + UUID.randomUUID());
    }

    /** The full form: {@code voidedAt} and {@code supersededBy} are the two non-state exclusions. */
    protected UUID seedSubscription(UUID userId, Plan tier, SubscriptionState state, Instant entitledUntil,
                                    Instant voidedAt, UUID supersededBy, String purchaseToken) {
        UUID id = UUID.randomUUID();
        String productId = switch (tier) {
            case STANDARD -> "whereis_standard_annual";
            case PRO -> "whereis_pro_annual";
            case MAX -> "whereis_max_annual";
            default -> throw new IllegalArgumentException(tier + " is not purchasable");
        };
        jdbc.update("insert into user_subscriptions (id, user_id, purchase_token, product_id, tier,"
                        + " provenance, state, entitled_until, acknowledged, voided_at, superseded_by,"
                        + " test_purchase, verified_at)"
                        + " values (?, ?, ?, ?, ?, 'PLAY_PURCHASE', ?, ?, true, ?, ?, false, now())",
                id, userId, purchaseToken, productId, tier.name(), state.name(),
                Timestamp.from(entitledUntil),
                voidedAt == null ? null : Timestamp.from(voidedAt), supersededBy);
        return id;
    }

    /**
     * Inserts {@code count} extra ACTIVE items at {@code locationId} in ONE statement — the cheap
     * way to stand an account next to the 100-item free-tier wall without 99 HTTP calls. Both the
     * guard and {@code GET /users/me/plan} count rows, so how the rows got there is irrelevant to
     * what those tests measure (the seeded rows have no history record, which neither reads).
     */
    protected void seedActiveItems(UUID userId, UUID locationId, int count) {
        jdbc.update("""
                insert into items (id, user_id, current_location_id, name, normalized_name, archived)
                select gen_random_uuid(), ?, ?, 'Seed ' || g, 'seed ' || g, false
                from generate_series(1, ?) g
                """, userId, locationId, count);
    }

    /**
     * POSTs one Pub/Sub push envelope to {@code /play/rtdn}, authenticated with both checks.
     *
     * <p>{@code String} rather than a DTO deliberately: the endpoint reads the body itself, and
     * several cases here are about bodies no DTO could represent.
     */
    protected ResponseEntity<String> postRtdn(String messageId, String base64Data) {
        return postRtdnRaw(RTDN_SECRET, RTDN_BEARER, envelopeJson(messageId, base64Data));
    }

    /** The unauthenticated / forged forms, and the malformed bodies. */
    protected ResponseEntity<String> postRtdnRaw(String key, String bearer, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        String url = key == null ? "/play/rtdn" : "/play/rtdn?key=" + key;
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    protected static String envelopeJson(String messageId, String base64Data) {
        return "{\"message\":{\"messageId\":\"" + messageId + "\",\"data\":\"" + base64Data
                + "\",\"publishTime\":\"2026-09-19T10:00:00Z\"},"
                + "\"subscription\":\"projects/p/subscriptions/s\"}";
    }

    /** A {@code subscriptionNotification} of the given type, base64 as Google sends it. */
    protected static String subscriptionNotification(long eventTimeMillis, int type, String purchaseToken) {
        return base64("{\"version\":\"1.0\",\"packageName\":\"az.technest.whereis\","
                + "\"eventTimeMillis\":\"" + eventTimeMillis + "\","
                + "\"subscriptionNotification\":{\"version\":\"1.0\",\"notificationType\":" + type
                + ",\"purchaseToken\":\"" + purchaseToken + "\","
                + "\"subscriptionId\":\"whereis_pro_annual\"}}");
    }

    /** A {@code voidedPurchaseNotification} — a SIBLING of the above, not a subtype. */
    protected static String voidedNotification(long eventTimeMillis, String purchaseToken, Integer productType) {
        return base64("{\"version\":\"1.0\",\"packageName\":\"az.technest.whereis\","
                + "\"eventTimeMillis\":\"" + eventTimeMillis + "\","
                + "\"voidedPurchaseNotification\":{\"purchaseToken\":\"" + purchaseToken + "\","
                + "\"orderId\":\"GS.0000-0000-0000\""
                + (productType == null ? "" : ",\"productType\":" + productType)
                + ",\"refundType\":1}}");
    }

    protected static String base64(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    protected HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    protected <T> ResponseEntity<T> get(String token, String url, Class<T> type) {
        return rest.exchange(url, org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(bearer(token)), type);
    }

    protected <T> ResponseEntity<T> post(String token, String url, Object body, Class<T> type) {
        return rest.exchange(url, org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, bearer(token)), type);
    }

    /** DELETE with a JSON body; {@code body == null} sends no body at all. */
    protected <T> ResponseEntity<T> deleteWithBody(String token, String url, Object body, Class<T> type) {
        return rest.exchange(url, HttpMethod.DELETE, new HttpEntity<>(body, bearer(token)), type);
    }

    /** Uploads a minimal valid JPEG to the item and returns the file id. */
    protected UUID uploadJpeg(String token, UUID itemId, boolean primary) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.IMAGE_JPEG);
        body.add("file", new HttpEntity<>(new ByteArrayResource(JPEG_BYTES) {
            @Override
            public String getFilename() {
                return "photo.jpg";
            }
        }, partHeaders));
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ItemFileResponse> uploaded = rest.exchange(
                "/api/v1/items/" + itemId + "/files?primary=" + primary, HttpMethod.POST,
                new HttpEntity<>(body, headers), ItemFileResponse.class);
        if (uploaded.getStatusCode() != HttpStatus.CREATED || uploaded.getBody() == null) {
            throw new IllegalStateException("Upload failed: " + uploaded.getStatusCode());
        }
        return uploaded.getBody().id();
    }

    protected SpaceResponse createSpace(String token, String name, SpaceType type) {
        ResponseEntity<SpaceResponse> response = post(token, "/api/v1/spaces",
                new CreateSpaceRequest(name, null, type), SpaceResponse.class);
        if (response.getBody() == null) {
            throw new IllegalStateException("Space creation failed: " + response.getStatusCode());
        }
        return response.getBody();
    }

    protected LocationResponse createLocation(String token, UUID spaceId, String name,
                                              LocationType type, UUID parentId) {
        ResponseEntity<LocationResponse> response = post(token, "/api/v1/spaces/" + spaceId + "/locations",
                new CreateLocationRequest(name, null, type, parentId), LocationResponse.class);
        if (response.getBody() == null || response.getBody().id() == null) {
            throw new IllegalStateException("Location creation failed: " + response.getStatusCode());
        }
        return response.getBody();
    }
}
