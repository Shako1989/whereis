package az.technest.whereis.plan;

import az.technest.whereis.common.error.ConflictException;
import az.technest.whereis.common.error.ErrorCode;

/**
 * A creation refused because the account's plan has no room left. Answered <strong>409</strong>,
 * like every other guard violation in this API ({@code SPACE_NOT_EMPTY}, {@code DUPLICATE_NAME}) —
 * deliberately not 402: the client branches on {@code code}, not on the status, and a 402 would
 * also promise a payment path that does not exist yet.
 *
 * <p>The message names both what was hit and what the limit is, because it is what the client shows
 * the user before offering to subscribe.
 */
public class PlanLimitReachedException extends ConflictException {

    private PlanLimitReachedException(String message) {
        super(ErrorCode.PLAN_LIMIT_REACHED, message);
    }

    public static PlanLimitReachedException spaces(int limit) {
        return new PlanLimitReachedException("Free plan limit reached: " + quantity(limit, "space", "spaces")
                + ". Subscribe for unlimited spaces.");
    }

    public static PlanLimitReachedException activeItems(int limit) {
        return new PlanLimitReachedException("Free plan limit reached: "
                + quantity(limit, "active item", "active items")
                + ". Archive an item to free room, or subscribe for unlimited items.");
    }

    private static String quantity(int limit, String singular, String plural) {
        return limit + " " + (limit == 1 ? singular : plural);
    }
}
