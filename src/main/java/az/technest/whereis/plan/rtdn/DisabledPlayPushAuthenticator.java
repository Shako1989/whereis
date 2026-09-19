package az.technest.whereis.plan.rtdn;

/**
 * The {@link PlayPushAuthenticator} of a deployment with no Play Billing configuration
 * ({@code whereis.play.rtdn.verifier=disabled}): <strong>nothing is ever genuine.</strong>
 *
 * <p><strong>Why the route stays and answers a fixed rejection, rather than disappearing.</strong>
 * Both were considered and this is the safer one, for three reasons:
 * <ol>
 *   <li><strong>The filter chain does not change shape.</strong> {@code PlayRtdnConfig}'s
 *       {@code @Order(0)} chain exists because on the main chain the bearer filter would hand
 *       Google's RS256 token to our HS256 decoder — the javadoc there calls that "the single most
 *       likely way to break this endpoint silently". Making the controller conditional would delete
 *       that chain, move {@code /play/rtdn} onto the main chain, and change which security
 *       configuration serves the path depending on an environment variable. A deny-all port swaps
 *       one implementation of one interface and leaves the topology identical.</li>
 *   <li><strong>The answer is one already-tested answer.</strong> 401 with a zero-length body and NO
 *       ledger row is exactly what every failed authentication produces today, so this mode inherits
 *       that behaviour instead of inventing a 404 nothing has exercised.</li>
 *   <li><strong>The rejection is doubled, not moved.</strong> Check 1 (the shared secret) is blank
 *       in this mode and blank REJECTS, so a caller is refused before this class is consulted; this
 *       class then refuses independently, which is what keeps the guarantee true even if somebody
 *       later sets a shared secret without setting a verifier.</li>
 * </ol>
 *
 * <p>The consequence worth stating plainly: under this mode the push endpoint <strong>cannot</strong>
 * become a route to an unverified notification, because there is no input for which it answers
 * anything but 401 — which makes it strictly safer than {@code google} (accepts genuine Google
 * tokens) and than {@code fake} (accepts one literal), not a loophole in either.
 */
public class DisabledPlayPushAuthenticator implements PlayPushAuthenticator {

    /**
     * Always {@code false} — for a null token, a blank one, a forged one and a genuinely
     * Google-signed one alike. There is deliberately no parameter inspection at all: a branch here
     * is a branch that could one day return true.
     */
    @Override
    public boolean isGenuine(String bearerToken) {
        return false;
    }
}
