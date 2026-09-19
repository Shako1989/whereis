package az.technest.whereis.plan.rtdn;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the Pub/Sub push endpoint. Nothing here is validated at bind time — the checks
 * live in {@code PlayRtdnConfig} so they fire once, loudly, at startup, and so a {@code fake}
 * deployment still binds with no Google values at all.
 *
 * <p><strong>Blank is never "skip the check".</strong> A blank shared secret and a blank fake
 * bearer both REJECT every request rather than accepting every request. That direction is chosen
 * deliberately: a rejected genuine push is a nack that Pub/Sub retries and a backlog we can repair,
 * while an accepted forged push hands an account any tier, permanently.
 *
 * @param enabled            when false the endpoint answers 503, which Pub/Sub retries — we want
 *                           the backlog, not silence
 * @param verifier           {@code google} (default) verifies Google's OIDC push token, or
 *                           {@code fake} compares one configured literal. The fake is REFUSED under
 *                           the prod profile. The default is deliberately NOT a mirror of
 *                           {@code whereis.play.provider}: a deployment running
 *                           {@code provider=google} with this unset must still be protected
 * @param sharedSecret       check 1. The {@code ?key=} query parameter of the registered push URL,
 *                           compared with {@code MessageDigest.isEqual}. Blank rejects everything
 * @param audience           check 2's {@code aud} claim. <strong>Must be an explicit opaque string
 *                           configured on the Pub/Sub subscription</strong> — see
 *                           {@code PlayRtdnConfig}, which refuses to boot on a URL-shaped value
 * @param serviceAccountEmail check 2's {@code email} claim: the service account Pub/Sub signs with
 * @param jwkSetUri          Google's public keys; Nimbus caches and rotates them, and the fetch is
 *                           lazy so building the bean touches no network
 * @param fakeBearer         the literal the fake verifier accepts. Blank rejects everything
 * @param maxAttempts        the retry ceiling. <strong>Must equal the literal in
 *                           {@code ix_play_notifications_unprocessed}</strong> (V10:
 *                           {@code attempts < 10}) — the index silently stops matching the work
 *                           queue the moment somebody retunes this, and nothing else would notice.
 *                           {@code RtdnConfigurationTest} parses V10 and asserts they agree
 * @param maxPayloadBytes    the body cap, enforced before anything is parsed
 */
@ConfigurationProperties("whereis.play.rtdn")
public record RtdnProperties(Boolean enabled, String verifier, String sharedSecret, String audience,
                             String serviceAccountEmail, String jwkSetUri, String fakeBearer,
                             int maxAttempts, int maxPayloadBytes) {

    public static final String FAKE = "fake";
    public static final String GOOGLE = "google";

    static final String DEFAULT_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";
    static final int DEFAULT_MAX_ATTEMPTS = 10;
    static final int DEFAULT_MAX_PAYLOAD_BYTES = 256 * 1024;

    public RtdnProperties {
        // Absent means ON: the endpoint is the whole point of this wave, and a deployment that
        // forgets the flag must receive notifications rather than silently drop them.
        enabled = enabled == null || enabled;
        // DEFAULTS TO google, NOT fake. whereis.play.provider defaults to fake because a dev box
        // must boot with no Google key; this one defaults to the STRICT value because the failure
        // modes are not symmetric — an unverified push endpoint is a self-service entitlement grant.
        verifier = verifier == null || verifier.isBlank() ? GOOGLE : verifier.trim().toLowerCase(Locale.ROOT);
        sharedSecret = sharedSecret == null ? "" : sharedSecret.trim();
        audience = audience == null ? "" : audience.trim();
        serviceAccountEmail = serviceAccountEmail == null ? "" : serviceAccountEmail.trim();
        jwkSetUri = jwkSetUri == null || jwkSetUri.isBlank() ? DEFAULT_JWK_SET_URI : jwkSetUri.trim();
        fakeBearer = fakeBearer == null ? "" : fakeBearer.trim();
        maxAttempts = maxAttempts <= 0 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        maxPayloadBytes = maxPayloadBytes <= 0 ? DEFAULT_MAX_PAYLOAD_BYTES : maxPayloadBytes;
    }
}
