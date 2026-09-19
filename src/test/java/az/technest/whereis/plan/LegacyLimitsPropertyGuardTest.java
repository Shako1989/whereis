package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The rename from {@code whereis.limits.free.*} is silent-failure-shaped: Spring ignores an unknown
 * property and the new defaults are the same two numbers, so a stale {@code .env} on a box looks
 * healthy while production tuning has quietly reverted. This turns it into a boot failure.
 */
class LegacyLimitsPropertyGuardTest {

    @Test
    void aStaleFreeTierPropertyFailsStartupAndNamesItsReplacement() {
        MockEnvironment stale = new MockEnvironment();
        stale.setProperty("whereis.limits.free.items", "500");

        assertThatThrownBy(() -> new LegacyLimitsPropertyGuard(stale).refuseRetiredKeys())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.limits.free.items was renamed")
                .hasMessageContaining("whereis.plans.free.items")
                .hasMessageContaining("WHEREIS_PLANS_FREE_ITEMS")
                .hasMessageContaining("raising a lower tier requires raising every tier above it");
    }

    @Test
    void aCleanEnvironmentBootsQuietly() {
        assertThatCode(() -> new LegacyLimitsPropertyGuard(new MockEnvironment()).refuseRetiredKeys())
                .doesNotThrowAnyException();
    }
}
