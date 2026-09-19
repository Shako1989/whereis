package az.technest.whereis.plan.rtdn;

import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Selects the {@link PlayPushAuthenticator}, refuses every configuration that would leave the push
 * endpoint unprotected or silently broken, and keeps the JWT resource-server filter off
 * {@code /play/rtdn}.
 */
@Configuration
public class PlayRtdnConfig {

    private static final Profiles PROD = Profiles.of("prod");

    @Bean
    public PlayPushAuthenticator playPushAuthenticator(RtdnProperties properties, Environment environment) {
        assertAudienceIsNotTheUrl(properties);
        return switch (properties.verifier()) {
            case RtdnProperties.FAKE -> {
                // Its own Environment check, NOT a mirror of PlayConfig's guard on
                // whereis.play.provider: a deployment with provider=google and the verifier left as
                // fake would otherwise have no guard at all, which is the exact silent
                // misconfiguration the two-check design exists to prevent.
                if (environment.acceptsProfiles(PROD)) {
                    throw new IllegalStateException(
                            "whereis.play.rtdn.verifier=fake is refused under the prod profile: it accepts a "
                                    + "literal bearer value, and this endpoint can grant any account any tier. "
                                    + "Set PLAY_RTDN_VERIFIER=google.");
                }
                yield new FakePlayPushAuthenticator(properties);
            }
            case RtdnProperties.GOOGLE -> new GooglePlayPushAuthenticator(properties);
            default -> throw new IllegalStateException("Unknown whereis.play.rtdn.verifier '"
                    + properties.verifier() + "' (supported: " + RtdnProperties.FAKE + ", "
                    + RtdnProperties.GOOGLE + ")");
        };
    }

    /**
     * <strong>The audience trap, refused at startup.</strong> Cloud Pub/Sub's OIDC push
     * configuration makes {@code audience} OPTIONAL, and when it is omitted Google sets the
     * {@code aud} claim to the full push endpoint URL — which here is
     * {@code https://host/play/rtdn?key=<the shared secret>}, query string included.
     *
     * <p>That leaves an operator two bad choices: every genuine push is rejected on {@code aud}
     * (entitlements quietly stop tracking Google, the worst outcome this design has), or they
     * "fix" it by pasting the URL into this property — which writes the shared secret into a config
     * file, the process environment, and every startup log that echoes bound properties, collapsing
     * two independent checks into one compromised value.
     *
     * <p>So a URL-shaped audience is a boot failure naming the trap, in the same shape as
     * {@code LegacyLimitsPropertyGuard}'s refusal. The runbook's answer is a fixed opaque string
     * such as {@code whereis-rtdn}, set explicitly on the subscription.
     */
    private static void assertAudienceIsNotTheUrl(RtdnProperties properties) {
        String audience = properties.audience().toLowerCase(Locale.ROOT);
        if (audience.startsWith("http") || audience.contains("?") || audience.contains("key=")) {
            throw new IllegalStateException(
                    "whereis.play.rtdn.audience looks like the push URL rather than an audience. "
                            + "Cloud Pub/Sub sets aud to the full endpoint URL — shared secret and all — "
                            + "when the subscription leaves the audience blank. Set an explicit opaque "
                            + "audience on the subscription (e.g. 'whereis-rtdn') and put THAT here; never "
                            + "the URL, which would put PLAY_RTDN_SHARED_SECRET into configuration and logs.");
        }
    }

    /**
     * <strong>The single most likely way to break this endpoint silently.</strong> On the main
     * chain, {@code BearerTokenAuthenticationFilter} would take Google's RS256 push token out of
     * the {@code Authorization} header, hand it to our HS256 {@code NimbusJwtDecoder}, fail, and
     * answer 401 before the controller ever ran. {@code permitAll} does not help — it governs
     * authorization, not decoding. Every notification would be rejected, Pub/Sub would retry each
     * one for the subscription's retention and then drop it, and the only symptom would be
     * entitlements that quietly stop tracking Google.
     *
     * <p>This is the same trap {@code SecurityConfig} already documents for {@code /auth/**}.
     * {@code PlayRtdnIT} asserts that a request carrying a syntactically valid but FOREIGN
     * {@code Authorization: Bearer} value still reaches the controller; that test fails if anyone
     * ever collapses the chains.
     *
     * <p>Ordered 0 so it wins the match ahead of {@code authFilterChain} (1) and the main chain (2).
     */
    @Bean
    @org.springframework.core.annotation.Order(0)
    public SecurityFilterChain playRtdnFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(RtdnController.PATH)
                .csrf(AbstractHttpConfigurer::disable)
                // Not CORS-exposed: CorsConfigurationSource registers /api/** only, and a browser
                // has no business here.
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        // NO .oauth2ResourceServer(...) — deliberately. See the javadoc above.
        return http.build();
    }
}
