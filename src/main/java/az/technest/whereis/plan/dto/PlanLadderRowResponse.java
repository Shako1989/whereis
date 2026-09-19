package az.technest.whereis.plan.dto;

import az.technest.whereis.plan.Plan;

/**
 * One row of {@code GET /api/v1/plans} — the four-tier ladder, straight from {@code PlanCatalog},
 * costing no statements at all because it is pure configuration.
 *
 * <p>No prices: a server-side price is wrong for most countries and is grounds for store
 * rejection. The client pairs {@code productId} with Play's own {@code ProductDetails} for the
 * price and the trial length.
 *
 * <p>{@code UNLIMITED} is deliberately absent from the ladder: it is not purchasable, and listing
 * it would invite a client to render it as an option.
 *
 * @param tier      the tier, in ladder order within the response array
 * @param productId the Play product that buys it, or {@code null} for FREE
 * @param limits    what it allows; members are null where nothing is limited
 */
public record PlanLadderRowResponse(Plan tier, String productId, PlanLimitsResponse limits) {
}
