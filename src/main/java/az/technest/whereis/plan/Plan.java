package az.technest.whereis.plan;

/**
 * What an account is entitled to. Persisted on {@code users.plan} as varchar and pinned by the
 * {@code ck_users_plan} CHECK in V9 — the constant names must match it byte for byte
 * ({@code PlanTest} guards the drift).
 *
 * <ul>
 *   <li>{@link #FREE} — the default for every account, new or existing: at most
 *       {@code whereis.limits.free.spaces} spaces and {@code whereis.limits.free.items} ACTIVE
 *       items. Nothing was grandfathered, by design (see V9).</li>
 *   <li>{@link #UNLIMITED} — a GRANT, made by an operator with a single UPDATE (deploy/README.md).
 *       Named after its effect rather than its reason ("internal", "staff", "comp") so the reason
 *       can change without a rename.</li>
 * </ul>
 *
 * <p>This is NOT subscription state and must never become it: see
 * {@link PlanLimitEnforcer#hasUnlimitedEntitlement}.
 */
public enum Plan {
    FREE, UNLIMITED
}
