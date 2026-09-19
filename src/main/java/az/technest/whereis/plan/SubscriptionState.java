package az.technest.whereis.plan;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Google's subscription state, mapped through our own enum. Persisted on
 * {@code user_subscriptions.state} and pinned by {@code ck_user_subscriptions_state}
 * ({@code SubscriptionStateTest} parses V10).
 *
 * <p>{@link #UNKNOWN} is a real stored value, not an error: an unmapped state must be recorded
 * truthfully, and it must not entitle.
 */
public enum SubscriptionState {

    ACTIVE,
    CANCELED,
    IN_GRACE_PERIOD,
    ON_HOLD,
    PAUSED,
    EXPIRED,
    PENDING,
    PENDING_PURCHASE_CANCELED,
    UNKNOWN;

    private static final String WIRE_PREFIX = "SUBSCRIPTION_STATE_";

    /**
     * THE ENTITLING STATES, AS JPQL. This constant and {@link #entitles()} are the same fact, and
     * {@code SubscriptionStateTest} asserts they stay that way — the state list is the one part of
     * the entitlement rule that unavoidably exists in both Java and JPQL, so it is written once
     * here and interpolated into {@code UserSubscriptionRepository}'s single {@code @Query}.
     *
     * <p>ACTIVE / IN_GRACE_PERIOD / CANCELED entitle. CANCELED means auto-renew is off but the paid
     * term is not over. PAUSED and ON_HOLD do NOT, even with a future expiry.
     */
    public static final String ENTITLING_STATES_JPQL =
            "az.technest.whereis.plan.SubscriptionState.ACTIVE, "
                    + "az.technest.whereis.plan.SubscriptionState.IN_GRACE_PERIOD, "
                    + "az.technest.whereis.plan.SubscriptionState.CANCELED";

    /**
     * Google returns the state as a STRING like {@code "SUBSCRIPTION_STATE_ACTIVE"} through the
     * generated client. Comparing that to {@code "ACTIVE"} compiles and matches nothing — which is
     * why nobody may compare raw Google strings anywhere but here. Never throws: ANYTHING unmapped,
     * including {@code SUBSCRIPTION_STATE_UNSPECIFIED}, {@code null}, and a state Google adds after
     * this ships, becomes {@link #UNKNOWN} — and UNKNOWN DENIES.
     */
    public static SubscriptionState fromWire(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        if (name.startsWith(WIRE_PREFIX)) {
            name = name.substring(WIRE_PREFIX.length());
        }
        for (SubscriptionState state : values()) {
            if (state.name().equals(name)) {
                return state;
            }
        }
        return UNKNOWN;
    }

    /** Whether a row in this state entitles its owner, given the other three predicates hold. */
    public boolean entitles() {
        return this == ACTIVE || this == IN_GRACE_PERIOD || this == CANCELED;
    }

    /** The entitling constants, for the drift test that compares them with the JPQL constant. */
    static String entitlingNames() {
        return Arrays.stream(values())
                .filter(SubscriptionState::entitles)
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }
}
