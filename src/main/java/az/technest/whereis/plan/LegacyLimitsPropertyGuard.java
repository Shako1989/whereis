package az.technest.whereis.plan;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start while the retired {@code whereis.limits.free.*} keys are still set anywhere.
 *
 * <p>The rename to {@code whereis.plans.free.*} is silent-failure-shaped: Spring ignores an unknown
 * property, and the new defaults are the same two numbers, so a stale {@code .env} on a box looks
 * completely healthy while any production tuning has quietly reverted to 1 and 100. Ten lines here
 * turn that into a boot failure that names the new key. The environment's own passthrough is the
 * other half — see deploy/README.md Step 10, which this message points at.
 */
@Component
@RequiredArgsConstructor
public class LegacyLimitsPropertyGuard {

    private static final String[] RETIRED = {"whereis.limits.free.spaces", "whereis.limits.free.items"};

    private final Environment environment;

    @PostConstruct
    void refuseRetiredKeys() {
        for (String key : RETIRED) {
            if (environment.containsProperty(key)) {
                throw new IllegalStateException(key + " was renamed and no longer does anything. Use "
                        + key.replace("whereis.limits.free.", "whereis.plans.free.")
                        + " (WHEREIS_PLANS_FREE_SPACES / WHEREIS_PLANS_FREE_ITEMS) instead, and remember that "
                        + "raising a lower tier requires raising every tier above it — see deploy/README.md Step 10.");
            }
        }
    }
}
