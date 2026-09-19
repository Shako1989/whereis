package az.technest.whereis.plan.dto;

/**
 * The ceilings a FREE account is held to, straight from {@code whereis.limits.free.*} — the same
 * object the guard compares against, so the client never hardcodes a number that lives in config.
 *
 * <p>The field names mirror the configuration keys ({@code spaces}, {@code items}) rather than
 * {@code PlanUsageResponse}'s {@code activeItems}. The asymmetry is deliberate: a limit is the
 * product rule as configured, while the usage field has to say precisely what it counted, because
 * "items" would read as "all items" and archived ones do not count.
 *
 * @param spaces maximum number of spaces
 * @param items  maximum number of ACTIVE (non-archived) items across all spaces
 */
public record PlanLimitsResponse(int spaces, int items) {
}
