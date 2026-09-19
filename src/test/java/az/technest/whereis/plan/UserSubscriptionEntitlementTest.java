package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * THE ENTITLING PREDICATE, one row at a time. The same four conditions live in
 * {@code UserSubscriptionRepository#entitlingOf} as JPQL; {@code PlanTierTransitionIT}
 * proves the two agree against a real database, and this pins what they both mean.
 */
class UserSubscriptionEntitlementTest {

    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");

    private static UserSubscription.UserSubscriptionBuilder entitling() {
        return UserSubscription.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .purchaseToken("token")
                .productId("whereis_pro_annual")
                .tier(Plan.PRO)
                .provenance(PurchaseProvenance.PLAY_PURCHASE)
                .state(SubscriptionState.ACTIVE)
                .entitledUntil(NOW.plus(Duration.ofDays(365)))
                .verifiedAt(NOW);
    }

    @ParameterizedTest
    @EnumSource(SubscriptionState.class)
    void exactlyThreeStatesEntitleAndTheRestDoNotEvenWithAFutureExpiry(SubscriptionState state) {
        UserSubscription row = entitling().state(state).build();

        // PAUSED and ON_HOLD have a future expiry here and still must not entitle: the state
        // predicate is what excludes them, and UNKNOWN denies because an unmapped state is a state
        // this server does not understand.
        assertThat(row.entitlesAt(NOW)).isEqualTo(state.entitles());
    }

    @Test
    void expiryIsStrictlyInTheFutureAndIsTheFailClosedGuard() {
        // Even if every RTDN is lost, entitlement lapses on its own when this passes. The boundary
        // is exclusive: at the instant of expiry the row no longer entitles.
        assertThat(entitling().entitledUntil(NOW.plusMillis(1)).build().entitlesAt(NOW)).isTrue();
        assertThat(entitling().entitledUntil(NOW).build().entitlesAt(NOW)).isFalse();
        assertThat(entitling().entitledUntil(NOW.minusMillis(1)).build().entitlesAt(NOW)).isFalse();
    }

    @Test
    void aRefundRevokesImmediatelyRatherThanAtExpiry() {
        UserSubscription voided = entitling().voidedAt(NOW.minus(Duration.ofDays(1))).build();

        assertThat(voided.entitlesAt(NOW)).isFalse();
    }

    @Test
    void theReplacedHalfOfAnUpgradeStopsCounting() {
        UserSubscription superseded = entitling().supersededBy(UUID.randomUUID()).build();

        assertThat(superseded.entitlesAt(NOW)).isFalse();
    }

    @Test
    void aCanceledRowWithAFutureExpiryStillEntitles() {
        // Auto-renew off is not "over". The user paid for the term and keeps it.
        UserSubscription canceled = entitling()
                .state(SubscriptionState.CANCELED)
                .entitledUntil(NOW.plus(Duration.ofDays(30)))
                .build();

        assertThat(canceled.entitlesAt(NOW)).isTrue();
    }
}
