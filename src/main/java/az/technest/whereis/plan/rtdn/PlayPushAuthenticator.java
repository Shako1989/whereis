package az.technest.whereis.plan.rtdn;

/**
 * Check 2 of the RTDN endpoint's two independent authentication checks: Google's OIDC push token.
 *
 * <p>Shaped exactly like {@code PlaySubscriptionsApi} and for the same reason — a real
 * implementation that needs the network and Google's JWKS, and a deterministic fake so
 * {@code ./gradlew build} and the whole integration suite run offline. The fake is refused under
 * the {@code prod} profile.
 */
public interface PlayPushAuthenticator {

    /**
     * @param bearerToken the raw value of the {@code Authorization} header with its {@code Bearer }
     *                    prefix already removed, or {@code null} when the header was absent
     * @return whether this is a token Google signed for our subscription. Never throws, never logs
     *         the token, and a blank configuration answers {@code false} rather than {@code true}
     */
    boolean isGenuine(String bearerToken);
}
