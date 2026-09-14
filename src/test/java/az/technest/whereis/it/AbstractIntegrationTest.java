package az.technest.whereis.it;

import az.technest.whereis.auth.dto.RegisterRequest;
import az.technest.whereis.auth.dto.TokenPairResponse;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.location.dto.CreateLocationRequest;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.CreateSpaceRequest;
import az.technest.whereis.space.dto.SpaceResponse;
import az.technest.whereis.storage.dto.ItemFileResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
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
    static final MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse(System.getProperty("it.minio.image", "minio/minio:RELEASE.2023-09-04T19-57-37Z"))
                    .asCompatibleSubstituteFor("minio/minio"));

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
    }

    /** The password every {@link #register()} call uses — needed by tests that re-authenticate. */
    protected static final String PASSWORD = "password123";

    /** Smallest byte sequence that passes the JPEG magic-byte check. */
    protected static final byte[] JPEG_BYTES =
            {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 10, 20, 30, 40, 50, 60, 70, 80};

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    protected TestRestTemplate rest;

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
