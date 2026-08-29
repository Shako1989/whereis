package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.auth.dto.LoginRequest;
import az.technest.whereis.auth.dto.RefreshRequest;
import az.technest.whereis.auth.dto.RegisterRequest;
import az.technest.whereis.auth.dto.TokenPairResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AuthFlowIT extends AbstractIntegrationTest {

    @Test
    void fullAuthLifecycleWithRotationAndReuseDetection() {
        String email = "auth-" + UUID.randomUUID() + "@example.com";

        ResponseEntity<TokenPairResponse> registered = rest.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "password123", "A", "B"), TokenPairResponse.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<TokenPairResponse> loggedIn = rest.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "password123"), TokenPairResponse.class);
        assertThat(loggedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        String refresh1 = loggedIn.getBody().refreshToken();

        // Rotation: the first refresh works and yields a new pair.
        ResponseEntity<TokenPairResponse> refreshed = rest.postForEntity("/api/v1/auth/refresh",
                new RefreshRequest(refresh1), TokenPairResponse.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String refresh2 = refreshed.getBody().refreshToken();
        assertThat(refresh2).isNotEqualTo(refresh1);

        // Reuse of the rotated token is rejected...
        ResponseEntity<String> reused = rest.postForEntity("/api/v1/auth/refresh",
                new RefreshRequest(refresh1), String.class);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // ...and revokes the whole family, killing the newer token too.
        ResponseEntity<String> familyRevoked = rest.postForEntity("/api/v1/auth/refresh",
                new RefreshRequest(refresh2), String.class);
        assertThat(familyRevoked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameGenericAnswer() {
        String email = "auth2-" + UUID.randomUUID() + "@example.com";
        rest.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "password123", null, null), TokenPairResponse.class);

        ResponseEntity<String> wrongPassword = rest.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "nope-nope-nope"), String.class);
        ResponseEntity<String> unknownEmail = rest.postForEntity("/api/v1/auth/login",
                new LoginRequest("ghost-" + UUID.randomUUID() + "@example.com", "whatever123"), String.class);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody()).contains("INVALID_CREDENTIALS");
        assertThat(unknownEmail.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void protectedEndpointsRequireAToken() {
        assertThat(rest.getForEntity("/api/v1/spaces", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
