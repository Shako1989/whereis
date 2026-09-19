package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.item.ItemRepository;
import az.technest.whereis.plan.PlanCatalog.TierConfig;
import az.technest.whereis.plan.dto.PlanLimitsResponse;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.space.SpaceRepository;
import az.technest.whereis.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class PlanLimitEnforcerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private SpaceRepository spaceRepository;
    @Mock
    private ItemRepository itemRepository;
    @Mock
    private UserSubscriptionRepository subscriptionRepository;

    private final UUID userId = UUID.randomUUID();

    /** The production ladder, so these cases and {@code PlanLimitIT} refuse at the same numbers. */
    private static final PlanCatalog CATALOG = shippedCatalog();

    private static PlanCatalog shippedCatalog() {
        Map<Plan, TierConfig> tiers = new EnumMap<>(Plan.class);
        tiers.put(Plan.FREE, new TierConfig(1, 100, null));
        tiers.put(Plan.STANDARD, new TierConfig(3, 300, "whereis_standard_annual"));
        tiers.put(Plan.PRO, new TierConfig(5, 600, "whereis_pro_annual"));
        tiers.put(Plan.MAX, new TierConfig(10, null, "whereis_max_annual"));
        tiers.put(Plan.UNLIMITED, new TierConfig(null, null, null));
        return new PlanCatalog(tiers);
    }

    private PlanLimitEnforcer enforcer;

    @BeforeEach
    void noSubscriptionsUnlessATestSaysOtherwise() {
        // Without this every case NPEs on an unstubbed finder rather than failing its assertion.
        lenient().when(subscriptionRepository.entitlingOf(eq(userId), any())).thenReturn(List.of());
        lenient().when(subscriptionRepository.manageableOf(eq(userId))).thenReturn(List.of());
        enforcer = new PlanLimitEnforcer(userRepository, spaceRepository, itemRepository,
                subscriptionRepository, CATALOG);
    }

    private void granted(Plan plan) {
        when(userRepository.findPlanById(userId)).thenReturn(Optional.of(plan));
    }

    private void subscribed(Plan tier) {
        when(subscriptionRepository.entitlingOf(eq(userId), any())).thenReturn(List.of(row(tier)));
    }

    private UserSubscription row(Plan tier) {
        return UserSubscription.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .purchaseToken("token-" + tier)
                .productId(CATALOG.of(tier).productId())
                .tier(tier)
                .provenance(PurchaseProvenance.PLAY_PURCHASE)
                .state(SubscriptionState.ACTIVE)
                .entitledUntil(Instant.now().plus(Duration.ofDays(365)))
                .acknowledged(true)
                .verifiedAt(Instant.now())
                .build();
    }

    // ------------------------------------------------------------------ the free tier, unchanged

    @Test
    void aFreeAccountWithNoSpaceYetMayCreateOne() {
        granted(Plan.FREE);
        when(spaceRepository.countByUserId(userId)).thenReturn(0L);

        enforcer.requireRoomForAnotherSpace(userId);
    }

    @Test
    void aFreeAccountAtTheSpaceLimitIsRefusedWithItsTierAndLimitInTheMessage() {
        granted(Plan.FREE);
        when(spaceRepository.countByUserId(userId)).thenReturn(1L);

        assertThatThrownBy(() -> enforcer.requireRoomForAnotherSpace(userId))
                .isInstanceOf(PlanLimitReachedException.class)
                .hasMessageContaining("Free plan limit reached: 1 space")
                .satisfies(thrown -> {
                    PlanLimitReachedException refusal = (PlanLimitReachedException) thrown;
                    assertThat(refusal.code()).isEqualTo(ErrorCode.PLAN_LIMIT_REACHED);
                    // 409, like every other guard violation here — not 402.
                    assertThat(refusal.status()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void theHundredthItemIsAllowedAndTheHundredAndFirstIsRefused() {
        granted(Plan.FREE);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(99L, 100L);

        enforcer.requireRoomForAnotherItem(userId);

        assertThatThrownBy(() -> enforcer.requireRoomForAnotherItem(userId))
                .isInstanceOf(PlanLimitReachedException.class)
                .hasMessageContaining("Free plan limit reached: 100 active items")
                .hasMessageContaining("Archive");
    }

    @Test
    void onlyActiveItemsAreCountedTowardsTheItemLimit() {
        granted(Plan.FREE);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(4L);

        enforcer.requireRoomForAnotherItem(userId);

        // The archived-excluding finder is the ONLY one consulted: archiving has to free room.
        verify(itemRepository).countByUserIdAndArchivedFalse(userId);
        verifyNoMoreInteractions(itemRepository);
    }

    @Test
    void anAccountThatNoLongerExistsIsTreatedAsFree() {
        when(userRepository.findPlanById(userId)).thenReturn(Optional.empty());
        when(spaceRepository.countByUserId(userId)).thenReturn(1L);

        assertThat(enforcer.effectiveTierOf(userId)).isEqualTo(Plan.FREE);
        // The limits apply rather than being waived: a guard's default must be the restrictive one.
        assertThatThrownBy(() -> enforcer.requireRoomForAnotherSpace(userId))
                .isInstanceOf(PlanLimitReachedException.class);
    }

    // -------------------------------------------------------------------------- the paid ladder

    @Test
    void aStandardSubscriberIsHeldToThreeSpacesAndThreeHundredItems() {
        granted(Plan.FREE);
        subscribed(Plan.STANDARD);
        when(spaceRepository.countByUserId(userId)).thenReturn(2L, 3L);

        enforcer.requireRoomForAnotherSpace(userId);

        assertThatThrownBy(() -> enforcer.requireRoomForAnotherSpace(userId))
                .isInstanceOf(PlanLimitReachedException.class)
                .hasMessageContaining("Standard plan limit reached: 3 spaces")
                .hasMessageContaining("Upgrade");
    }

    @Test
    void aProSubscriberIsHeldToFiveSpacesAndSixHundredItems() {
        granted(Plan.FREE);
        subscribed(Plan.PRO);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(600L);

        assertThatThrownBy(() -> enforcer.requireRoomForAnotherItem(userId))
                .isInstanceOf(PlanLimitReachedException.class)
                .hasMessageContaining("Pro plan limit reached: 600 active items");
    }

    @Test
    void maxIsRefusedAnEleventhSpaceButNeverAnItem() {
        granted(Plan.FREE);
        subscribed(Plan.MAX);
        when(spaceRepository.countByUserId(userId)).thenReturn(10L);

        // PER-ALLOWANCE nullability, which is the whole reason a single boolean was not enough:
        // Max has a finite ten spaces AND no item ceiling at all.
        assertThatThrownBy(() -> enforcer.requireRoomForAnotherSpace(userId))
                .isInstanceOf(PlanLimitReachedException.class)
                .hasMessage("Max plan limit reached: 10 spaces.");

        enforcer.requireRoomForAnotherItem(userId);
        verifyNoInteractions(itemRepository);
    }

    @Test
    void anUnlimitedGrantIsRefusedNothingAndNeverCounts() {
        granted(Plan.UNLIMITED);

        enforcer.requireRoomForAnotherSpace(userId);
        enforcer.requireRoomForAnotherItem(userId);

        assertThat(enforcer.effectiveTierOf(userId)).isEqualTo(Plan.UNLIMITED);
        verifyNoInteractions(spaceRepository, itemRepository);
    }

    // ------------------------------------------------------------------------------- the max()

    @Test
    void anOperatorGrantBeatsALowerSubscription() {
        granted(Plan.PRO);
        subscribed(Plan.STANDARD);

        // The entire reason billing does not write users.plan: a subscription must never be able to
        // downgrade a hand-made grant.
        assertThat(enforcer.effectiveTierOf(userId)).isEqualTo(Plan.PRO);
    }

    @Test
    void aSubscriptionBeatsALowerOrAbsentGrant() {
        granted(Plan.FREE);
        subscribed(Plan.MAX);

        assertThat(enforcer.effectiveTierOf(userId)).isEqualTo(Plan.MAX);
    }

    @Test
    void theBestOfSeveralEntitlingSubscriptionsWins() {
        granted(Plan.FREE);
        when(subscriptionRepository.entitlingOf(eq(userId), any()))
                .thenReturn(List.of(row(Plan.STANDARD), row(Plan.MAX), row(Plan.PRO)));

        // Reduced in Java, never ordered in SQL: @Enumerated(STRING) sorts 'MAX' before 'PRO' and
        // 'STANDARD', which is the ladder upside down.
        assertThat(enforcer.effectiveTierOf(userId)).isEqualTo(Plan.MAX);
    }

    @Test
    void anAccountWithNoEntitlingRowAtAllIsFree() {
        granted(Plan.FREE);

        assertThat(enforcer.effectiveTierOf(userId)).isEqualTo(Plan.FREE);
    }

    // ----------------------------------------------------------- the report agrees with the wall

    @Test
    void theGuardAndTheReportReachTheSameTierOnEveryCombination() {
        // THE CHOKEPOINT PROPERTY. effectiveTierOf and statusOf must not compute the rule twice:
        // if they ever disagree the screen says PRO while the wall says FREE. Grant-wins,
        // subscription-wins and the tie are all here.
        when(spaceRepository.countByUserId(userId)).thenReturn(0L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(0L);

        for (Plan grant : Plan.values()) {
            for (Plan subscription : List.of(Plan.STANDARD, Plan.PRO, Plan.MAX)) {
                when(userRepository.findPlanById(userId)).thenReturn(Optional.of(grant));
                when(subscriptionRepository.entitlingOf(eq(userId), any()))
                        .thenReturn(List.of(row(subscription)));

                assertThat(enforcer.statusOf(userId).plan())
                        .as("grant=" + grant + " subscription=" + subscription)
                        .isEqualTo(enforcer.effectiveTierOf(userId))
                        .isEqualTo(Plan.higherOf(grant, subscription));
            }
        }
    }

    @Test
    void aProSubscriberReportsProsLimitsAndTheSubscriptionItself() {
        granted(Plan.FREE);
        subscribed(Plan.PRO);
        when(spaceRepository.countByUserId(userId)).thenReturn(2L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(143L);

        PlanStatusResponse status = enforcer.statusOf(userId);

        assertThat(status.plan()).isEqualTo(Plan.PRO);
        assertThat(status.limits()).isEqualTo(new PlanLimitsResponse(5, 600));
        assertThat(status.source()).isEqualTo(EntitlementSource.SUBSCRIPTION);
        assertThat(status.subscription().productId()).isEqualTo("whereis_pro_annual");
        assertThat(status.subscription().tier()).isEqualTo(Plan.PRO);
        assertThat(status.subscription().state()).isEqualTo(SubscriptionState.ACTIVE);
    }

    @Test
    void maxReportsAFiniteSpaceCeilingBesideANullItemCeiling() {
        granted(Plan.FREE);
        subscribed(Plan.MAX);
        when(spaceRepository.countByUserId(userId)).thenReturn(4L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(1203L);

        PlanStatusResponse status = enforcer.statusOf(userId);

        assertThat(status.limits()).isEqualTo(new PlanLimitsResponse(10, null));
    }

    @Test
    void anOperatorGrantReportsBothCeilingsNullAndSourceGrant() {
        granted(Plan.UNLIMITED);
        when(spaceRepository.countByUserId(userId)).thenReturn(4L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(19L);

        PlanStatusResponse status = enforcer.statusOf(userId);

        assertThat(status.plan()).isEqualTo(Plan.UNLIMITED);
        assertThat(status.limits()).isEqualTo(new PlanLimitsResponse(null, null));
        assertThat(status.source()).isEqualTo(EntitlementSource.GRANT);
        assertThat(status.subscription()).isNull();
        // Usage is reported on every tier — the screen still shows "19 items".
        assertThat(status.usage().activeItems()).isEqualTo(19L);
    }

    @Test
    void aPaidSubscriptionIsReportedEvenUnderAHigherGrantSoItStaysManageable() {
        granted(Plan.UNLIMITED);
        subscribed(Plan.STANDARD);
        when(spaceRepository.countByUserId(userId)).thenReturn(1L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(1L);

        PlanStatusResponse status = enforcer.statusOf(userId);

        assertThat(status.plan()).isEqualTo(Plan.UNLIMITED);
        assertThat(status.source()).isEqualTo(EntitlementSource.GRANT);
        // The client's rule is "show Manage subscription iff subscription != null", so a real paid
        // subscription must be reported even when a grant decided the tier.
        assertThat(status.subscription()).isNotNull();
        assertThat(status.subscription().tier()).isEqualTo(Plan.STANDARD);
    }

    @Test
    void aSubscriptionWinsTheTieAgainstAGrantOfTheSameTier() {
        granted(Plan.PRO);
        subscribed(Plan.PRO);
        when(spaceRepository.countByUserId(userId)).thenReturn(0L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(0L);

        // Reasoning recorded in EntitlementSource: a real paid subscription must always be
        // manageable, so the copy must not call it a grant.
        assertThat(enforcer.statusOf(userId).source()).isEqualTo(EntitlementSource.SUBSCRIPTION);
    }

    @Test
    void aFreeAccountReportsSourceNone() {
        granted(Plan.FREE);
        when(spaceRepository.countByUserId(userId)).thenReturn(1L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(19L);

        PlanStatusResponse status = enforcer.statusOf(userId);

        assertThat(status.source()).isEqualTo(EntitlementSource.NONE);
        assertThat(status.subscription()).isNull();
        assertThat(status.limits()).isEqualTo(new PlanLimitsResponse(1, 100));
    }

    @Test
    void theReportedUsageIsTheSameNumberTheGuardRefusesOn() {
        granted(Plan.FREE);
        when(spaceRepository.countByUserId(userId)).thenReturn(1L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(100L);

        PlanStatusResponse status = enforcer.statusOf(userId);

        assertThat(status.usage().spaces()).isEqualTo(status.limits().spaces().longValue());
        assertThat(status.usage().activeItems()).isEqualTo(status.limits().items().longValue());
        assertThatThrownBy(() -> enforcer.requireRoomForAnotherSpace(userId))
                .isInstanceOf(PlanLimitReachedException.class);
        assertThatThrownBy(() -> enforcer.requireRoomForAnotherItem(userId))
                .isInstanceOf(PlanLimitReachedException.class);
    }

    @Test
    void anOverLimitAccountAfterADowngradeReportsUsageAboveItsLimitsAndIsRefusedOnlyCreation() {
        // Dropped from PRO (5 spaces) to STANDARD (3) while holding five. Nothing existing is taken
        // away — every read, rename, move, archive and delete path is untouched because none of them
        // calls this class at all (OwnershipScopingArchTest makes that a build failure). Only the
        // next creation is refused, and the report says why without clamping.
        granted(Plan.FREE);
        subscribed(Plan.STANDARD);
        when(spaceRepository.countByUserId(userId)).thenReturn(5L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(412L);

        PlanStatusResponse status = enforcer.statusOf(userId);

        assertThat(status.plan()).isEqualTo(Plan.STANDARD);
        assertThat(status.limits()).isEqualTo(new PlanLimitsResponse(3, 300));
        assertThat(status.usage().spaces()).isEqualTo(5L);
        assertThat(status.usage().activeItems()).isEqualTo(412L);
        assertThatThrownBy(() -> enforcer.requireRoomForAnotherSpace(userId))
                .isInstanceOf(PlanLimitReachedException.class);
    }

    // ------------------------------------------------------------------ manageableOf (wave 2)

    @Test
    void anOnHoldSubscriberReportsFreeAndStillGetsTheSubscriptionToManage() {
        // THE ONE RULE THE PLAN SCREEN HANGS ON. The badge describes the ENTITLEMENT, the strip
        // describes the SUBSCRIPTION, and neither is derived from the other. Wave 1 reported
        // subscription = null here, which takes away the only control that can fix a failed
        // payment — from an account that is still being charged.
        granted(Plan.FREE);
        UserSubscription onHold = row(Plan.PRO);
        onHold.setState(SubscriptionState.ON_HOLD);
        when(subscriptionRepository.entitlingOf(eq(userId), any())).thenReturn(List.of());
        when(subscriptionRepository.manageableOf(userId)).thenReturn(List.of(onHold));
        when(spaceRepository.countByUserId(userId)).thenReturn(0L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(0L);

        PlanStatusResponse status = enforcer.statusOf(userId);

        assertThat(status.plan()).isEqualTo(Plan.FREE);
        assertThat(status.limits()).isEqualTo(new PlanLimitsResponse(1, 100));
        assertThat(status.subscription()).isNotNull();
        assertThat(status.subscription().tier()).isEqualTo(Plan.PRO);
        assertThat(status.subscription().state()).isEqualTo(SubscriptionState.ON_HOLD);
        assertThat(status.subscription().entitling()).isFalse();
    }

    @Test
    void aLiveButNonEntitlingRowCanNeverInfluenceThePlanTheLimitsOrTheUsage() {
        // The first crack in "one finder", pinned so it stays a crack and not a hole: an entitling
        // STANDARD row beside a live ON_HOLD MAX row must badge STANDARD. A single reduction over
        // both would make the badge claim a tier the wall would refuse.
        granted(Plan.FREE);
        UserSubscription entitlingStandard = row(Plan.STANDARD);
        UserSubscription onHoldMax = row(Plan.MAX);
        onHoldMax.setState(SubscriptionState.ON_HOLD);
        when(subscriptionRepository.entitlingOf(eq(userId), any()))
                .thenReturn(List.of(entitlingStandard));
        lenient().when(subscriptionRepository.manageableOf(userId))
                .thenReturn(List.of(entitlingStandard, onHoldMax));
        when(spaceRepository.countByUserId(userId)).thenReturn(0L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(0L);

        PlanStatusResponse status = enforcer.statusOf(userId);

        assertThat(status.plan()).isEqualTo(Plan.STANDARD);
        assertThat(status.limits()).isEqualTo(new PlanLimitsResponse(3, 300));
        // The entitling row is also the one reported, so the badge and the strip agree.
        assertThat(status.subscription().tier()).isEqualTo(Plan.STANDARD);
        assertThat(status.subscription().entitling()).isTrue();
        assertThat(enforcer.effectiveTierOf(userId)).isEqualTo(Plan.STANDARD);
    }

    @Test
    void theSecondFinderIsNotEvenConsultedWhenSomethingEntitles() {
        // An entitling row is always manageable (manageableOf's predicate is strictly weaker), so
        // the common case costs no extra statement.
        granted(Plan.FREE);
        subscribed(Plan.PRO);
        when(spaceRepository.countByUserId(userId)).thenReturn(0L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(0L);

        enforcer.statusOf(userId);

        verify(subscriptionRepository, org.mockito.Mockito.never()).manageableOf(any());
    }

    @Test
    void aPendingDowngradeIsReportedAsAProductIdAndATierResolvedAtReadTime() {
        granted(Plan.FREE);
        UserSubscription pro = row(Plan.PRO);
        pro.setPendingProductId("whereis_standard_annual");
        when(subscriptionRepository.entitlingOf(eq(userId), any())).thenReturn(List.of(pro));
        when(spaceRepository.countByUserId(userId)).thenReturn(0L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(0L);

        PlanStatusResponse status = enforcer.statusOf(userId);

        assertThat(status.plan()).isEqualTo(Plan.PRO);
        assertThat(status.subscription().pendingProductId()).isEqualTo("whereis_standard_annual");
        assertThat(status.subscription().pendingTier()).isEqualTo(Plan.STANDARD);
    }

    @Test
    void aPendingProductThisDeploymentDoesNotConfigureReportsANullTierRatherThanAWrongOne() {
        granted(Plan.FREE);
        UserSubscription pro = row(Plan.PRO);
        pro.setPendingProductId("whereis_retired_annual");
        when(subscriptionRepository.entitlingOf(eq(userId), any())).thenReturn(List.of(pro));
        when(spaceRepository.countByUserId(userId)).thenReturn(0L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(0L);

        PlanStatusResponse status = enforcer.statusOf(userId);

        assertThat(status.subscription().pendingProductId()).isEqualTo("whereis_retired_annual");
        assertThat(status.subscription().pendingTier()).isNull();
    }

    @Test
    void theReportedSubscriptionIsDeterministicWhenTwoRowsShareATier() {
        granted(Plan.FREE);
        Instant now = Instant.now();
        UserSubscription shorter = row(Plan.PRO);
        shorter.setEntitledUntil(now.plus(Duration.ofDays(10)));
        shorter.setProductId("whereis_pro_annual");
        UserSubscription longer = row(Plan.PRO);
        longer.setEntitledUntil(now.plus(Duration.ofDays(400)));
        when(subscriptionRepository.entitlingOf(eq(userId), any()))
                .thenReturn(List.of(shorter, longer), List.of(longer, shorter));
        when(spaceRepository.countByUserId(userId)).thenReturn(0L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(0L);

        // Row order out of the database must not decide which subscription the screen names.
        assertThat(enforcer.statusOf(userId).subscription().entitledUntil())
                .isEqualTo(enforcer.statusOf(userId).subscription().entitledUntil())
                .isEqualTo(longer.getEntitledUntil());
    }
}
