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

    @Test
    void theShippedConfigurationDeclaresEveryRtdnKeyWithNoDefaultUnderProd() {
        // prod must not be able to start half-configured on the one endpoint that can hand any
        // account any tier: an unresolvable placeholder is the only acceptable outcome.
        String prod = resource("application-prod.yml");
        assertThat(prod)
                .contains("${PLAY_RTDN_VERIFIER}")
                .contains("${PLAY_RTDN_SHARED_SECRET}")
                .contains("${PLAY_RTDN_AUDIENCE}")
                .contains("${PLAY_RTDN_SERVICE_ACCOUNT_EMAIL}");
        assertThat(prod).doesNotContain("PLAY_RTDN_SHARED_SECRET:");
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
