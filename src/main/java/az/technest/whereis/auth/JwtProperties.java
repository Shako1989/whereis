package az.technest.whereis.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("security.jwt")
public record JwtProperties(String secret, Duration accessTtl, Duration refreshTtl) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "security.jwt.secret (JWT_SECRET) must be set and at least 32 bytes long");
        }
        if (accessTtl == null || accessTtl.isNegative() || accessTtl.isZero()) {
            throw new IllegalStateException("security.jwt.access-ttl must be positive");
        }
        if (refreshTtl == null || refreshTtl.isNegative() || refreshTtl.isZero()) {
            throw new IllegalStateException("security.jwt.refresh-ttl must be positive");
        }
    }

    public byte[] secretBytes() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }
}
