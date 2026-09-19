package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.technest.whereis.plan.play.PlayProperties;
import az.technest.whereis.plan.rtdn.RtdnProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The cross-property rule neither config class can see on its own: {@code disabled} means billing
 * is off, and billing has two halves.
 *
 * <p>Note what is NOT tested here, because it must stay untestable from this class: the prod
 * refusals of {@code fake}. Those live in {@code PlayConfigTest} and {@code RtdnConfigurationTest},
 * each keyed on its own property, and this guard deliberately does not touch them — it only ever
 * rejects MORE combinations.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlayBillingModeGuardTest {

    @Test
    void billingFullyOffIsAValidDeploymentAndIsTheOneProductionRunsToday() {
        assertThatCode(() -> guard("disabled", "disabled").refuseHalfDisabledBilling())
                .doesNotThrowAnyException();
    }

    @Test
    void billingFullyOnIsUnaffected() {
        assertThatCode(() -> guard("google", "google").refuseHalfDisabledBilling())
                .doesNotThrowAnyException();
    }

    @Test
    void theDevelopmentDefaultsAreUnaffected() {
        // A dev box runs provider=fake with the verifier left at its strict google default. That
        // combination predates this guard and must keep booting.
        assertThatCode(() -> guard("fake", "google").refuseHalfDisabledBilling())
                .doesNotThrowAnyException();
        assertThatCode(() -> guard("fake", "fake").refuseHalfDisabledBilling())
                .doesNotThrowAnyException();
    }

    /**
     * The expensive half-configuration: purchases verify and raise tiers, while every refund,
     * expiry and revocation Google pushes is answered 401. A revocation has no entry point other
     * than the notification and the voided sweep, so a refunded annual subscriber would keep a paid
     * tier for up to a year.
     */
    @Test
    void aWorkingPlayApiWithADeadPushEndpointIsRefusedAtStartup() {
        assertThatThrownBy(() -> guard("google", "disabled").refuseHalfDisabledBilling())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.play.provider=google")
                .hasMessageContaining("whereis.play.rtdn.verifier=disabled")
                .hasMessageContaining("PLAY_PROVIDER")
                .hasMessageContaining("PLAY_RTDN_VERIFIER")
                .hasMessageContaining("refunded subscriber would keep a paid tier");
    }

    /**
     * The noisy half-configuration: a genuine Google push authenticates and then fails inside the
     * handler, because every Play call refuses — a FAILED ledger row and a 500 per delivery,
     * retried for the subscription's whole retention, that can never succeed.
     */
    @Test
    void aLivePushEndpointWithNoPlayApiBehindItIsRefusedAtStartup() {
        assertThatThrownBy(() -> guard("disabled", "google").refuseHalfDisabledBilling())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.play.provider=disabled")
                .hasMessageContaining("whereis.play.rtdn.verifier=google")
                .hasMessageContaining("cannot serve the notifications");
    }

    private static PlayBillingModeGuard guard(String provider, String verifier) {
        return new PlayBillingModeGuard(
                new PlayProperties(provider, "az.technest.whereis", null, Duration.ofSeconds(10)),
                new RtdnProperties(true, verifier, "secret", "whereis-rtdn", "svc@example.com",
                        null, "bearer", 10, 262144));
    }
}
