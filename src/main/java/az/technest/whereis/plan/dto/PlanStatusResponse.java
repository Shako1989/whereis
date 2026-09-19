package az.technest.whereis.plan.dto;

import az.technest.whereis.plan.EntitlementSource;
import az.technest.whereis.plan.Plan;

/**
 * What the caller's plan allows and how much of it is used — the whole body of
 * {@code GET /api/v1/users/me/plan}, and also the whole body of
 * {@code POST /api/v1/users/me/plan/purchases}, so a purchase result and the plan screen cannot
 * disagree.
 *
 * <pre>
 * { "plan": "PRO",
 *   "limits": {"spaces": 5, "items": 600},
 *   "usage":  {"spaces": 2, "activeItems": 143},
 *   "source": "SUBSCRIPTION",
 *   "subscription": {"productId": "whereis_pro_annual", "tier": "PRO", "state": "ACTIVE",
 *                    "entitledUntil": "2027-09-19T10:04:00Z", "provenance": "PLAY_PURCHASE",
 *                    "acknowledged": true} }
 *
 * { "plan": "MAX",       "limits": {"spaces": 10,   "items": null}, "source": "SUBSCRIPTION", ... }
 * { "plan": "UNLIMITED", "limits": {"spaces": null, "items": null}, "source": "GRANT", "subscription": null }
 * { "plan": "FREE",      "limits": {"spaces": 1,    "items": 100},  "source": "NONE",  "subscription": null }
 * </pre>
 *
 * <p><strong>THE ONE DELIBERATE CONTRACT CHANGE (supersedes BR-11).</strong> {@code limits} is now
 * ALWAYS a non-null object and the nullability moved to its two MEMBERS. BR-11 documented
 * "{@code limits} is null for UNLIMITED"; that is superseded here because MAX ("10 spaces,
 * unlimited items") cannot be expressed by a whole-object null at all. One rule survives, and it is
 * simpler than the one it replaces: <em>a null is exactly one thing — no ceiling on that
 * allowance.</em>
 *
 * <p>{@code plan} is the caller's <em>effective entitlement</em>, never a copy of a column:
 * {@code max(users.plan, best entitling subscription)} from
 * {@code PlanLimitEnforcer#effectiveTierOf}, the one method the guards ask, so the screen and the
 * wall cannot disagree. An operator grant therefore always wins and a subscription can never
 * downgrade a granted account.
 *
 * @param plan         the effective tier
 * @param limits       what that tier may hold; members are null where nothing is limited
 * @param usage        how much of it exists right now — always reported, on every tier, and never
 *                     clamped ({@code usage.spaces = 5} against {@code limits.spaces = 3} after a
 *                     downgrade is a valid, expected body)
 * @param source       which side of the max() decided the tier; for copy, not for behaviour
 * @param subscription the best entitling subscription, or {@code null} when there is none
 */
public record PlanStatusResponse(Plan plan, PlanLimitsResponse limits, PlanUsageResponse usage,
                                 EntitlementSource source, PlanSubscriptionResponse subscription) {
}
