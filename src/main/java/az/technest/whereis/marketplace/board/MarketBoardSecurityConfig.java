package az.technest.whereis.marketplace.board;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * The marketplace board is served by a chain with <strong>NO {@code oauth2ResourceServer}</strong>,
 * for exactly the reason {@code authFilterChain} and {@code playRtdnFilterChain} are:
 * {@code permitAll} governs AUTHORIZATION, not DECODING. {@code BearerTokenAuthenticationFilter}
 * runs first, so a {@code permitAll} matcher on the main chain would answer 401 to any request
 * carrying an expired or malformed token.
 *
 * <p><strong>For this path that is the normal case, not an edge case.</strong>
 * {@code security.jwt.access-ttl} is 15 minutes and the Android client browses this board
 * continuously while holding a token; a board on the main chain would work for fifteen minutes
 * after each foreground and then 401 until something else happened to trigger a refresh. It would
 * also pass every test written by someone holding a freshly minted token.
 *
 * <p><strong>THE CONSEQUENCE, and it is the rule for this whole subtree:</strong> a request here is
 * ALWAYS anonymous, even when it carries a valid token. {@code CurrentUser.id()} would throw from
 * anywhere under this matcher, and {@code MarketplacePublicSurfaceArchTest} makes that a build
 * failure rather than a runtime discovery. Nothing on the board varies by viewer — there are no
 * favourites, no "hide my own" and no messaging — and anything that later needs a principal
 * belongs at a DIFFERENT path, exactly as {@code SecurityConfig}'s javadoc warns for
 * {@code /auth/**}.
 *
 * <p>{@code anyRequest().denyAll()} rather than {@code permitAll()}, which is where this differs
 * from {@code playRtdnFilterChain}: that chain matches ONE exact path, this one matches a subtree
 * that will grow. A route added here without its own matcher is refused rather than silently
 * anonymous-writable.
 *
 * <p>Ordered 1, ahead of {@code authFilterChain} (2) and the catch-all (3). That is the only
 * ordering constraint that matters — this matcher, {@code /api/v1/auth/**} and {@code /play/rtdn}
 * are mutually disjoint.
 */
@Configuration
public class MarketBoardSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain marketBoardFilterChain(HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .securityMatcher(MarketBoardController.PATH + "/**")
                .csrf(AbstractHttpConfigurer::disable)
                // The shared source already registers /api/**, so the board inherits the same
                // origins as every other API path. Not widened to "*": that would buy a web board
                // that does not exist, and turning it back off later is a breaking change.
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // GET and HEAD, the /legal/** lesson verbatim: share-link previewers and
                        // link checkers probe with HEAD, and a GET-only matcher answers those 401.
                        .requestMatchers(HttpMethod.GET, MarketBoardController.PATH + "/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, MarketBoardController.PATH + "/**").permitAll()
                        // The ONLY anonymous write in this application, matched by its exact shape.
                        .requestMatchers(HttpMethod.POST,
                                MarketBoardController.PATH + "/listings/*/reports").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().denyAll());
        // NO .oauth2ResourceServer(...) — deliberately. See the javadoc above.
        return http.build();
    }
}
