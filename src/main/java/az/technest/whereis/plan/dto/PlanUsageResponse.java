package az.technest.whereis.plan.dto;

/**
 * How much of the plan exists right now, counted in the database by the same two {@code count}
 * queries the guard compares against — never by loading rows, and never by a second query written
 * separately (which is how the number on the upgrade screen would drift from the number that
 * refuses the next creation).
 *
 * @param spaces         spaces the account owns
 * @param activeItems    items that are NOT archived; archiving lowers this and frees room
 * @param activeListings listings still on the board; marking one sold or withdrawing it lowers
 *                       this and frees room. A listing an OPERATOR has hidden still counts — the
 *                       cap must not be freed by a moderation action, or the seller re-publishes
 *                       the same thing immediately.
 */
public record PlanUsageResponse(long spaces, long activeItems, long activeListings) {
}
