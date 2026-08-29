package az.technest.whereis.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.technest.whereis.user.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdefghijklmnop";

    private final JwtProperties properties =
            new JwtProperties(SECRET, Duration.ofMinutes(15), Duration.ofDays(30));
    private final JwtService jwtService =
            new JwtService(new NimbusJwtEncoder(new ImmutableSecret<>(properties.secretBytes())), properties);
    private final NimbusJwtDecoder decoder = NimbusJwtDecoder
            .withSecretKey(new SecretKeySpec(properties.secretBytes(), "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();

    @Test
    void accessTokenCarriesSubjectEmailAndExpiry() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).email("user@example.com").passwordHash("x").build();

        Jwt jwt = decoder.decode(jwtService.createAccessToken(user));

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("email")).isEqualTo("user@example.com");
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(14)));
        assertThat(jwt.getExpiresAt()).isBefore(Instant.now().plus(Duration.ofMinutes(16)));
    }

    @Test
    void tamperedTokenIsRejected() {
        User user = User.builder().id(UUID.randomUUID()).email("user@example.com").passwordHash("x").build();
        String token = jwtService.createAccessToken(user);
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageTokenIsRejected() {
        assertThatThrownBy(() -> decoder.decode("not-a-jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtProperties("too-short", Duration.ofMinutes(15), Duration.ofDays(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
