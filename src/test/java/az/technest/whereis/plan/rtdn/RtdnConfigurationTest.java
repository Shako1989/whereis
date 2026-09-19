package az.technest.whereis.plan.rtdn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The configuration of the push endpoint, which is the part a review found most likely to be
 * silently wrong: a default that reads as safe and is not, a blank value that reads as "skip" and
 * must read as "reject", and an audience that Google fills in with the push URL when the Console
 * leaves it empty.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RtdnConfigurationTest {

    @Test
    void theVerifierDefaultsToGoogleAndNotToFake() {
        // NOT a mirror of whereis.play.provider, which defaults to fake so a dev box boots with no
        // Google key. The failure modes are not symmetric: an unverified push endpoint IS a
        // self-service entitlement grant, so the default here is the strict one.
        assertThat(blank().verifier()).isEqualTo(RtdnProperties.GOOGLE);
    }

    @Test
    void anAbsentEnabledFlagMeansOnRatherThanOff() {
        assertThat(blank().enabled()).isTrue();
    }

    @Test
    void aBlankFakeBearerRejectsEveryRequestRatherThanAcceptingThem() {
        // Otherwise every non-prod deployment would be protected by a URL query parameter alone —
        // the one thing reverse proxies log.
        FakePlayPushAuthenticator authenticator = new FakePlayPushAuthenticator(blank());

        assertThat(authenticator.isGenuine(null)).isFalse();
        assertThat(authenticator.isGenuine("")).isFalse();
        assertThat(authenticator.isGenuine("anything")).isFalse();
    }

    @Test
    void aConfiguredFakeBearerMatchesOnlyItself() {
        FakePlayPushAuthenticator authenticator =
                new FakePlayPushAuthenticator(withVerifier("fake", "whereis-rtdn", "let-me-in"));

        assertThat(authenticator.isGenuine("let-me-in")).isTrue();
        assertThat(authenticator.isGenuine("let-me-in ")).isFalse();
        assertThat(authenticator.isGenuine("let-me-i")).isFalse();
        assertThat(authenticator.isGenuine(null)).isFalse();
    }

    @Test
    void theFakeVerifierIsRefusedUnderTheProdProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> new PlayRtdnConfig()
                .playPushAuthenticator(withVerifier("fake", "whereis-rtdn", "x"), prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLAY_RTDN_VERIFIER=google");
    }

    @Test
    void anUnknownVerifierIsAStartupFailureRatherThanASilentDefault() {
        assertThatThrownBy(() -> new PlayRtdnConfig()
                .playPushAuthenticator(withVerifier("maybe", "whereis-rtdn", "x"), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.play.rtdn.verifier");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://whereis.example.com/play/rtdn?key=s3cr3t",
            "http://whereis.example.com/play/rtdn",
            "whereis.example.com/play/rtdn?key=s3cr3t",
            "something-with-key=inside"
    })
    void aUrlShapedAudienceIsABootFailureNamingTheTrap(String audience) {
        // Cloud Pub/Sub's OIDC push config makes `audience` OPTIONAL, and when it is omitted Google
        // sets `aud` to the FULL push endpoint URL — query string and shared secret included. The
        // operator's obvious "fix" is to paste that URL here, which writes the secret into
        // configuration, the environment and every startup log that echoes bound properties, and
        // collapses two independent checks into one compromised value.
        assertThatThrownBy(() -> new PlayRtdnConfig()
                .playPushAuthenticator(withVerifier("fake", audience, "x"), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.play.rtdn.audience");
    }

    @Test
    void anOpaqueAudienceIsAccepted() {
        assertThat(new PlayRtdnConfig()
                .playPushAuthenticator(withVerifier("fake", "whereis-rtdn", "x"), new MockEnvironment()))
                .isInstanceOf(FakePlayPushAuthenticator.class);
    }

    @Test
    void theRetryCeilingEqualsTheLiteralInTheWorkQueueIndex() {
        // ix_play_notifications_unprocessed is `WHERE processed_at IS NULL AND attempts < 10`. If
        // somebody retunes whereis.play.rtdn.max-attempts, the index silently stops matching the
        // work queue it exists to serve — a poison message would drop out of the index before the
        // handler stopped retrying it, or the other way round — and NOTHING else would notice.
        Matcher matcher = Pattern.compile("attempts\\s*<\\s*(\\d+)").matcher(migration("V10__subscriptions.sql"));
        assertThat(matcher.find()).as("the attempts literal in V10's work-queue index").isTrue();

        assertThat(blank().maxAttempts()).isEqualTo(Integer.parseInt(matcher.group(1)));
    }

    /**
     * <strong>The prod profile now BOOTS with no Play values at all, and that is the change this
     * test exists to pin.</strong> These four keys used to be declared with no default, so an
     * absent variable was an unresolvable placeholder and a startup failure — which is
     * incompatible with a billing-not-configured mode whose whole premise is that they are absent.
     *
     * <p>What replaced it is the pair below: a SAFE default (deny-everything, blank) plus a
     * conditional refusal in {@link PlayRtdnConfig} that fires only under {@code verifier=google}.
     * A placeholder cannot express "required only when another property has a particular value",
     * which is exactly why the check had to move into the application.
     */
    @Test
    void theShippedProdConfigurationDefaultsToDenyingEverythingRatherThanFailingToStart() {
        String prod = resource("application-prod.yml");

        assertThat(prod)
                .contains("${PLAY_RTDN_VERIFIER:disabled}")
                .contains("${PLAY_RTDN_SHARED_SECRET:}")
                .contains("${PLAY_RTDN_AUDIENCE:}")
                .contains("${PLAY_RTDN_SERVICE_ACCOUNT_EMAIL:}")
                .contains("${PLAY_PROVIDER:disabled}");
        // Still no literal secret anywhere in a committed file: the only permitted default is empty.
        assertThat(prod).doesNotContain("${PLAY_RTDN_SHARED_SECRET:-");
    }

    /**
     * The other half, and the one that carries the risk of this change: with compose no longer
     * marking these {@code :?}-required, a typo in a variable name must not produce a running,
     * billing-shaped deployment. Every one of them blank REJECTS rather than skips, so the failure
     * it would otherwise cause is silent — the push endpoint answering 401 to Google forever.
     */
    @ParameterizedTest
    @ValueSource(strings = {"shared-secret", "audience", "service-account-email"})
    void theGoogleVerifierRefusesToStartWhenAnyOfItsCredentialsIsBlank(String missing) {
        RtdnProperties properties = new RtdnProperties(true, "google",
                "shared-secret".equals(missing) ? "  " : "a-secret",
                "audience".equals(missing) ? "" : "whereis-rtdn",
                "service-account-email".equals(missing) ? null : "svc@example.com",
                null, "", 10, 262144);

        assertThatThrownBy(() -> new PlayRtdnConfig().playPushAuthenticator(properties, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.play.rtdn." + missing)
                .hasMessageContaining("PLAY_RTDN_VERIFIER=disabled");
    }

    @Test
    void aFullyConfiguredGoogleVerifierStartsWithoutTouchingTheNetwork() {
        // The JWKS fetch is lazy, so building the bean must not need Google to be reachable.
        assertThat(new PlayRtdnConfig().playPushAuthenticator(
                withVerifier("google", "whereis-rtdn", ""), new MockEnvironment()))
                .isInstanceOf(GooglePlayPushAuthenticator.class);
    }

    /**
     * <strong>The third verifier, and the claim that has to be verified rather than asserted:
     * under it the push endpoint cannot become a route to an unverified notification.</strong>
     * Unlike the fake it is PERMITTED under prod, because the two are opposite risks — the fake
     * accepts one literal, this accepts nothing.
     */
    @Test
    void theDisabledVerifierIsPermittedUnderProdAndDeniesEveryPossibleInput() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod", "json");

        PlayPushAuthenticator authenticator = new PlayRtdnConfig()
                .playPushAuthenticator(withVerifier("disabled", "", ""), prod);

        assertThat(authenticator).isInstanceOf(DisabledPlayPushAuthenticator.class);
        assertThat(authenticator.isGenuine(null)).isFalse();
        assertThat(authenticator.isGenuine("")).isFalse();
        assertThat(authenticator.isGenuine("   ")).isFalse();
        assertThat(authenticator.isGenuine("let-me-in")).isFalse();
        // A structurally valid, Google-shaped OIDC token: still false. There is no input for which
        // this answers true, which is what makes the mode strictly safer than google or fake.
        assertThat(authenticator.isGenuine("eyJhbGciOiJSUzI1NiIsImtpZCI6ImFiYyJ9.eyJhdWQiOiJ3aGVyZWlzLXJ0"
                + "ZG4iLCJlbWFpbCI6InN2Y0BleGFtcGxlLmNvbSJ9.c2ln")).isFalse();
    }

    /** Whatever is configured alongside it, the disabled verifier still denies. */
    @Test
    void theDisabledVerifierIgnoresAConfiguredFakeBearerAndSharedSecret() {
        PlayPushAuthenticator authenticator = new PlayRtdnConfig()
                .playPushAuthenticator(withVerifier("disabled", "whereis-rtdn", "let-me-in"),
                        new MockEnvironment());

        assertThat(authenticator.isGenuine("let-me-in")).isFalse();
    }

    private static RtdnProperties blank() {
        return new RtdnProperties(null, null, null, null, null, null, null, 0, 0);
    }

    private static RtdnProperties withVerifier(String verifier, String audience, String bearer) {
        return new RtdnProperties(true, verifier, "secret", audience, "svc@example.com", null,
                bearer, 10, 262144);
    }

    private static String migration(String name) {
        return resource("db/migration/" + name);
    }

    private static String resource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
