package az.technest.whereis.plan;

/**
 * Which side of {@code max(grant, subscription)} decided the caller's effective tier — the field
 * BR-11 deferred ("telling a grant apart from a subscription ... will need its own field").
 *
 * <p>It is for COPY, not for behaviour: the client shows "Manage subscription" when
 * {@code subscription != null}, regardless of this value, because a paid subscription must always
 * be manageable even by an account that also holds a higher operator grant.
 */
public enum EntitlementSource {

    /** The effective tier is FREE: nothing is granted and nothing is subscribed. */
    NONE,

    /** An operator grant on {@code users.plan} is strictly higher than any entitling subscription. */
    GRANT,

    /**
     * A paid subscription decides the tier. Wins the tie when a grant and a subscription name the
     * same tier, on the reasoning that a real paid subscription must always be manageable.
     */
    SUBSCRIPTION
}
