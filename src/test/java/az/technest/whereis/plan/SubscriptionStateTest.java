package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.migration.Migrations;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code user_subscriptions.state} vs {@link SubscriptionState}, and the one place the entitlement
 * rule unavoidably exists twice.
 */
class SubscriptionStateTest {

    @Test
    void constantsMatchTheEffectiveCheckByteForByte() {
        List<String> allowed = Migrations.effectiveCheckValues("ck_user_subscriptions_state", "state");

        assertThat(allowed).containsExactly("ACTIVE", "CANCELED", "IN_GRACE_PERIOD", "ON_HOLD", "PAUSED",
                "EXPIRED", "PENDING", "PENDING_PURCHASE_CANCELED", "UNKNOWN");
        assertThat(Arrays.stream(SubscriptionState.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(allowed);
    }

    @Test
    void theJpqlStateListIsTheSameSetAsEntitles() {
        // The entitling states are written in Java (entitles()) and in JPQL (the repository's one
        // @Query). This is the drift that a comment cannot prevent: add a future grace variant to
        // entitles() but not to the query, and a purchase verifies 200 and then reads as FREE.
        List<String> inJpql = Arrays.stream(SubscriptionState.ENTITLING_STATES_JPQL.split(","))
                .map(String::trim)
                .map(fqn -> fqn.substring(fqn.lastIndexOf('.') + 1))
                .toList();

        assertThat(inJpql).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(SubscriptionState.values())
                        .filter(SubscriptionState::entitles)
                        .map(Enum::name)
                        .toList());
        // And the fully-qualified form the query actually interpolates must resolve.
        assertThat(SubscriptionState.ENTITLING_STATES_JPQL)
                .contains("az.technest.whereis.plan.SubscriptionState.ACTIVE");
    }

    @Test
    void exactlyThreeStatesEntitle() {
        assertThat(Arrays.stream(SubscriptionState.values()).filter(SubscriptionState::entitles).toList())
                .containsExactly(SubscriptionState.ACTIVE, SubscriptionState.CANCELED,
                        SubscriptionState.IN_GRACE_PERIOD);
        // CANCELED entitles: auto-renew is off but the paid term is not over. PAUSED and ON_HOLD do
        // NOT, even with a future expiry — that is the whole difference between the two halves.
        assertThat(SubscriptionState.PAUSED.entitles()).isFalse();
        assertThat(SubscriptionState.ON_HOLD.entitles()).isFalse();
        assertThat(SubscriptionState.UNKNOWN.entitles()).isFalse();
    }

    @Test
    void googlesWireFormIsStrippedAndAnythingUnmappedDenies() {
        assertThat(SubscriptionState.fromWire("SUBSCRIPTION_STATE_ACTIVE")).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(SubscriptionState.fromWire("SUBSCRIPTION_STATE_IN_GRACE_PERIOD"))
                .isEqualTo(SubscriptionState.IN_GRACE_PERIOD);
        // Accepted unprefixed too, so a future client shape does not silently deny everything.
        assertThat(SubscriptionState.fromWire("active")).isEqualTo(SubscriptionState.ACTIVE);

        // Everything else is UNKNOWN, and UNKNOWN denies. This is the branch that stops a state
        // Google adds after this ships from being read as entitling.
        assertThat(SubscriptionState.fromWire("SUBSCRIPTION_STATE_UNSPECIFIED")).isEqualTo(SubscriptionState.UNKNOWN);
        assertThat(SubscriptionState.fromWire("SUBSCRIPTION_STATE_SOMETHING_NEW")).isEqualTo(SubscriptionState.UNKNOWN);
        assertThat(SubscriptionState.fromWire(null)).isEqualTo(SubscriptionState.UNKNOWN);
        assertThat(SubscriptionState.fromWire("")).isEqualTo(SubscriptionState.UNKNOWN);
        assertThat(SubscriptionState.fromWire("ACTIVE_BUT_NOT_REALLY")).isEqualTo(SubscriptionState.UNKNOWN);
    }
}
