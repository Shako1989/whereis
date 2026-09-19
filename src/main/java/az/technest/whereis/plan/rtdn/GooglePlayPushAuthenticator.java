package az.technest.whereis.plan.rtdn;

import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Verifies the RS256 OIDC token Cloud Pub/Sub attaches to a push request when the subscription is
 * configured with a service-account identity.
 *
 * <p>Five independent claims must hold, and each closes a different mistake:
 * <ul>
 *   <li>the signature, against Google's rotating JWKS (Nimbus caches it; the fetch is LAZY, on
 *       first decode, so constructing this bean touches no network and {@code ./gradlew build}
 *       stays offline);</li>
 *   <li>{@code exp}/{@code nbf} with 60 s of clock skew;</li>
 *   <li>{@code iss} ∈ {@code accounts.google.com};</li>
 *   <li>{@code aud} equal to the configured audience — which is why the runbook REQUIRES an
 *       explicit audience on the Pub/Sub subscription. Left blank there, Google sets {@code aud} to
 *       the full push endpoint URL, query string and shared secret included;</li>
 *   <li>{@code email} equal to the configured service account, and {@code email_verified} true —
 *       without this, ANY Google-issued OIDC token for ANY project passes the first four.</li>
 * </ul>
 *
 * <p>Never throws, and never logs a PURCHASE token: a failure is {@code false} plus one WARN naming the
 * reason.
 */
@Slf4j
public class GooglePlayPushAuthenticator implements PlayPushAuthenticator {

    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final NimbusJwtDecoder decoder;

    public GooglePlayPushAuthenticator(RtdnProperties properties) {
        NimbusJwtDecoder built = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        built.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtTimestampValidator(java.time.Duration.ofSeconds(60)),
                claim("iss", jwt -> jwt.getIssuer() != null && ISSUERS.contains(jwt.getIssuer().toString())),
                claim("aud", jwt -> jwt.getAudience() != null
                        && jwt.getAudience().contains(properties.audience())),
                claim("email", jwt -> properties.serviceAccountEmail().equals(jwt.getClaimAsString("email"))),
                claim("email_verified", jwt -> Boolean.TRUE.equals(jwt.getClaim("email_verified"))))));
        this.decoder = built;
    }

    @Override
    public boolean isGenuine(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return false;
        }
        try {
            decoder.decode(bearerToken);
            return true;
        } catch (RuntimeException e) {
            // The message names which validator objected. It is Nimbus's, not ours, and for a
            // malformed JWT it can quote the offending segment — so this stays WARN and is NOT a
            // promise that nothing token-shaped appears. It is Google's push token either way,
            // never a purchase token, and it has already failed verification.
            log.warn("Rejected an RTDN push: the OIDC token did not verify ({})", e.getMessage());
            return false;
        }
    }

    private static OAuth2TokenValidator<Jwt> claim(String name, java.util.function.Predicate<Jwt> test) {
        return jwt -> test.test(jwt)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "the " + name + " claim is not ours", null));
    }
}
