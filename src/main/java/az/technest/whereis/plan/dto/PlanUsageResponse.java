package az.technest.whereis.plan.dto;

/**
 * How much of the plan exists right now, counted in the database by the same two {@code count}
 * queries the guard compares against — never by loading rows, and never by a second query written
 * separately (which is how the number on the upgrade screen would drift from the number that
 * refuses the next creation).
 *
 * @param spaces      spaces the account owns
 * @param activeItems items that are NOT archived; archiving lowers this and frees room
 */
public record PlanUsageResponse(long spaces, long activeItems) {
}
