package az.technest.whereis.plan;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What a {@link Plan#FREE} account may create, bound from {@code whereis.limits.free.*}.
 *
 * <p>Configuration rather than constants: the two numbers are a product decision that will be
 * tuned during closed testing, and a tuning run must not need a code change. Relaxed binding means
 * {@code WHEREIS_LIMITS_FREE_ITEMS} overrides the value in {@code application.yml} on any
 * environment without a redeploy.
 *
 * <p>Both are counts of what EXISTS, not of what was ever created: deleting a space or archiving an
 * item frees room again (see {@link PlanLimitEnforcer}).
 *
 * @param spaces maximum number of spaces
 * @param items  maximum number of ACTIVE (non-archived) items across all spaces
 */
@ConfigurationProperties("whereis.limits.free")
public record FreeTierLimits(int spaces, int items) {

    public FreeTierLimits {
        if (spaces < 1) {
            throw new IllegalStateException("whereis.limits.free.spaces must be at least 1, was " + spaces);
        }
        if (items < 1) {
            throw new IllegalStateException("whereis.limits.free.items must be at least 1, was " + items);
        }
    }
}
