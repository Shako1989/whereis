package az.technest.whereis.plan.dto;

/**
 * The ceilings the caller's EFFECTIVE tier is held to, straight from {@code whereis.plans.*} — the
 * same numbers the guard compares against, so the client never hardcodes a value that lives in
 * config.
 *
 * <p><strong>Both members are nullable, and a {@code null} is exactly one thing: no ceiling on THAT
 * allowance.</strong> This supersedes BR-11's "{@code limits} is null for UNLIMITED": the moment
 * MAX exists ("10 spaces, unlimited ITEMS") a whole-object null cannot express the state at all, so
 * per-allowance nulls are mandatory. Keeping both representations would give "everything is
 * unlimited" two encodings, which is precisely the "states one fact twice, so the wire can
 * contradict itself" defect the original DTO javadoc rejected. The object itself is therefore now
 * ALWAYS present.
 *
 * <pre>
 * {"spaces": 5,    "items": 500}    // PRO
 * {"spaces": 10,   "items": null}   // MAX   — finite spaces, unlimited items
 * {"spaces": null, "items": null}   // UNLIMITED (the operator grant)
 * </pre>
 *
 * <p>The field names mirror the configuration keys ({@code spaces}, {@code items}) rather than
 * {@code PlanUsageResponse}'s {@code activeItems}. The asymmetry is deliberate: a limit is the
 * product rule as configured, while the usage field has to say precisely what it counted, because
 * "items" would read as "all items" and archived ones do not count.
 *
 * @param spaces   maximum number of spaces, or {@code null} for no ceiling on spaces
 * @param items    maximum number of ACTIVE items, or {@code null} for no ceiling on items
 * @param listings maximum simultaneously ACTIVE marketplace listings, or {@code null} for no
 *                 ceiling. MAX is the tier that makes the per-allowance null unavoidable: its
 *                 {@code items} is null and its {@code listings} is NOT, because an unbounded
 *                 public surface per account is a spam vector in a way a private inventory is not.
 */
public record PlanLimitsResponse(Integer spaces, Integer items, Integer listings) {
}
