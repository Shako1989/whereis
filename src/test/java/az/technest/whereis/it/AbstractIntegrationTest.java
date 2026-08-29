package az.technest.whereis.it;

import az.technest.whereis.auth.dto.RegisterRequest;
import az.technest.whereis.auth.dto.TokenPairResponse;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.location.dto.CreateLocationRequest;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.CreateSpaceRequest;
import az.technest.whereis.space.dto.SpaceResponse;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

    @Autowired
    protected TestRestTemplate rest;

    protected String registerAndGetToken() {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<TokenPairResponse> response = rest.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "password123", "Test", "User"), TokenPairResponse.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Registration failed: " + response.getStatusCode());
        }
        return response.getBody().accessToken();
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
