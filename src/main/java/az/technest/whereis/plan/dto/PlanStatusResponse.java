package az.technest.whereis.plan.dto;

import az.technest.whereis.plan.Plan;

/**
 * What the caller's plan allows and how much of it is used — the whole body of
 * {@code GET /api/v1/users/me/plan}, and the only way a client can learn the free-tier numbers
 * without being refused first.
 *
 * <pre>
 * { "plan": "FREE",      "limits": {"spaces": 1, "items": 100}, "usage": {"spaces": 1, "activeItems": 19} }
 * { "plan": "UNLIMITED", "limits": null,                        "usage": {"spaces": 4, "activeItems": 19} }
 * </pre>
 *
 * <p><strong>{@code limits} is {@code null} for an UNLIMITED account and the key is always
 * present.</strong> Three shapes were possible and this one was chosen deliberately:
 * <ul>
 *   <li><em>null</em> (chosen) — "no limits apply" is the same fact the guard implements by
 *       returning before it counts anything, and a null object says it once. The key is emitted
 *       because nothing in this service configures Jackson's inclusion, so every response here
 *       carries all of its fields; a per-DTO {@code @JsonInclude} would make this one response the
 *       exception. An always-present key also means the client branches on one condition
 *       ({@code limits == null}) instead of two ("absent or null"), and the OpenAPI schema shows a
 *       nullable field rather than an optional one.</li>
 *   <li><em>absent</em> — same meaning, but only for parsers that treat a missing key as null; it
 *       is a decoder setting on the client, not a property of the wire.</li>
 *   <li><em>the numbers plus an {@code unlimited} flag</em> — rejected: it states the same fact
 *       twice, so {@code {"unlimited": true, "spaces": 1}} is representable and self-contradictory,
 *       and a client that ignored the flag would render a limit that is not enforced.</li>
 * </ul>
 *
 * <p>{@code plan} is the caller's <em>effective entitlement</em>, not a copy of a column: it is
 * derived from {@code PlanLimitEnforcer#hasUnlimitedEntitlement}, the one method the guards ask, so
 * the screen and the wall cannot disagree. Today that is exactly {@code users.plan}; when billing
 * lands and the rule becomes "granted OR subscribed", a subscriber reads {@code UNLIMITED} here
 * while {@code users.plan} stays {@code FREE}. Telling a grant apart from a subscription is a
 * different question (it needs a "manage subscription" button) and will need its own field.
 *
 * @param plan   what the caller is entitled to
 * @param limits what a FREE account may hold, or {@code null} when nothing is limited
 * @param usage  how much of it exists right now — always reported, on both plans
 */
public record PlanStatusResponse(Plan plan, PlanLimitsResponse limits, PlanUsageResponse usage) {
}
