package az.technest.whereis.plan;

import az.technest.whereis.plan.play.PlayProperties;
import az.technest.whereis.plan.rtdn.RtdnProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when only ONE half of billing is switched off: {@code disabled} must be set on
 * both {@code whereis.play.provider} and {@code whereis.play.rtdn.verifier}, or on neither.
 *
 * <p>Same shape as {@link LegacyLimitsPropertyGuard} — one {@code @PostConstruct}, one message that
 * names both offending keys and both environment variables, in the style {@code PlanCatalog}
 * already uses for the ladder monotonicity failure. It lives here rather than in either config
 * class because it is the only rule that needs to see both records, and neither
 * {@code PlayConfig} nor {@code PlayRtdnConfig} can express it alone.
 *
 * <p><strong>Both mixtures are real failures, not tidiness.</strong>
 * <ul>
 *   <li>{@code provider=google} + {@code verifier=disabled}: purchases verify and raise tiers,
 *       while every refund, expiry, pause and revocation Google pushes is answered 401. The
 *       reconciler papers over drift within 15 minutes, but a REVOCATION has no other entry point
 *       than the notification and the six-hourly sweep — so a refunded annual subscriber keeps a
 *       paid tier. The spec names "entitlements quietly stop tracking Google" as the worst outcome
 *       in this design; this is the configuration that produces it.</li>
 *   <li>{@code provider=disabled} + {@code verifier=google}: the endpoint would authenticate a
 *       genuine Google push and then fail inside the handler, because every Play call refuses. That
 *       is a {@code FAILED} ledger row and a 500 per delivery, retried by Pub/Sub for the
 *       subscription's whole retention — noise that cannot ever succeed.</li>
 * </ul>
 *
 * <p><strong>What this deliberately does NOT do:</strong> it does not make either prod refusal of
 * {@code fake} depend on the other property. Those two checks stay exactly as they were, each
 * keyed on its own key, because that independence is what protects a deployment running
 * {@code provider=google} with the verifier left unset. A cross-property CONSISTENCY check is
 * additive — it can only reject more combinations — so it cannot weaken them.
 */
@Component
@RequiredArgsConstructor
public class PlayBillingModeGuard {

    private final PlayProperties play;
    private final RtdnProperties rtdn;

    @PostConstruct
    void refuseHalfDisabledBilling() {
        boolean apiOff = PlayProperties.DISABLED.equals(play.provider());
        boolean pushOff = RtdnProperties.DISABLED.equals(rtdn.verifier());
        if (apiOff == pushOff) {
            return;
        }
        throw new IllegalStateException("Billing is half configured: whereis.play.provider="
                + play.provider() + " (PLAY_PROVIDER) and whereis.play.rtdn.verifier="
                + rtdn.verifier() + " (PLAY_RTDN_VERIFIER). '" + PlayProperties.DISABLED
                + "' switches billing OFF and must be set on BOTH or NEITHER: "
                + (apiOff
                        ? "a disabled Play API cannot serve the notifications this verifier would accept."
                        : "a disabled push verifier rejects every refund, expiry and revocation Google "
                                + "sends, so a refunded subscriber would keep a paid tier.")
                + " See deploy/README.md Step 11e.");
    }
}
