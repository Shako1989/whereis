package az.technest.whereis.plan;

/**
 * What an account is entitled to — a LADDER, weakest first. Persisted on {@code users.plan} as
 * varchar and pinned by the {@code ck_users_plan} CHECK (V9, widened by V10); the constant names
 * must match it byte for byte and {@code PlanTest} guards the drift across every migration.
 *
 * <ul>
 *   <li>{@link #FREE} — the default for every account, new or existing.</li>
 *   <li>{@link #STANDARD}, {@link #PRO}, {@link #MAX} — the purchasable ladder. Each names a Play
 *       product in {@code whereis.plans.*.product-id}; the numbers live in the same configuration
 *       ({@link PlanCatalog}), never in this file. {@code MAX} has UNLIMITED ITEMS but a FINITE ten
 *       spaces, which is why a limit is nullable PER ALLOWANCE rather than all-or-nothing.</li>
 *   <li>{@link #UNLIMITED} — a GRANT, made by an operator with a single UPDATE
 *       (deploy/README.md Step 10). Never purchasable, and deliberately unrepresentable in
 *       {@code user_subscriptions.tier}, so "granted" can always be told apart from "paid".</li>
 * </ul>
 *
 * <p><strong>The ladder IS the declaration order</strong> — there is no rank field, because a rank
 * field and a declaration order are two statements of one fact that can disagree. The fragility
 * that buys (inserting a constant in the middle silently re-ranks everything) is closed by
 * {@code PlanTest#theLadderIsOrderedWeakestFirst}, not by a comment.
 *
 * <p>TWO TRAPS:
 * <ol>
 *   <li>NEVER {@code order by} an {@code @Enumerated(STRING)} column to find the highest tier.
 *       Alphabetically 'MAX' &lt; 'PRO' &lt; 'STANDARD', which is the ladder upside down. Reduce in
 *       Java with {@link #higherOf}.</li>
 *   <li>{@link #valueOf} must never be reachable from client input. Product id to tier goes through
 *       {@link PlanCatalog#tierOf(String)}, which returns an {@link java.util.Optional}.</li>
 * </ol>
 *
 * <p>This is NOT subscription state and must never become it: see
 * {@link PlanLimitEnforcer#effectiveTierOf(java.util.UUID)}, which combines this column with the
 * subscription table by taking the HIGHER of the two rather than letting either overwrite the
 * other.
 */
public enum Plan {

    FREE,
    STANDARD,
    PRO,
    MAX,
    UNLIMITED;

    /** Whether this tier is at least as generous as {@code other} on the ladder. */
    public boolean isAtLeast(Plan other) {
        return compareTo(other) >= 0;
    }

    /** The more generous of two tiers. The whole entitlement rule is one call to this. */
    public static Plan higherOf(Plan a, Plan b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /**
     * Buyable through Play. {@link #FREE} is not a purchase and {@link #UNLIMITED} is an operator
     * grant — {@code ck_user_subscriptions_tier} enforces the same set in the database, and
     * {@code SubscriptionTierTest} pins the two together.
     */
    public boolean isPurchasable() {
        return this == STANDARD || this == PRO || this == MAX;
    }

    /** "Pro", "Max", "Free" — for the one message a user reads, {@code PlanLimitReachedException}. */
    public String displayName() {
        return name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT);
    }
}
