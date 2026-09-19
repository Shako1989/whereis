package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.item.ItemRepository;
import az.technest.whereis.plan.dto.PlanLimitsResponse;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.plan.dto.PlanUsageResponse;
import az.technest.whereis.space.SpaceRepository;
import az.technest.whereis.user.UserRepository;
import java.util.Optional;
import java.util.UUID;
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

    private final UUID userId = UUID.randomUUID();

    private PlanLimitEnforcer enforcer(int spaces, int items) {
        return new PlanLimitEnforcer(userRepository, spaceRepository, itemRepository,
                new FreeTierLimits(spaces, items));
    }

    private void plan(Plan plan) {
        when(userRepository.findPlanById(userId)).thenReturn(Optional.of(plan));
    }

    @Test
    void aFreeAccountWithNoSpaceYetMayCreateOne() {
        plan(Plan.FREE);
        when(spaceRepository.countByUserId(userId)).thenReturn(0L);

        enforcer(1, 100).requireRoomForAnotherSpace(userId);
    }

    @Test
    void aFreeAccountAtTheSpaceLimitIsRefusedWithTheLimitInTheMessage() {
        plan(Plan.FREE);
        when(spaceRepository.countByUserId(userId)).thenReturn(1L);

        assertThatThrownBy(() -> enforcer(1, 100).requireRoomForAnotherSpace(userId))
                .isInstanceOf(PlanLimitReachedException.class)
                .hasMessageContaining("1 space")
                .satisfies(thrown -> {
                    PlanLimitReachedException refusal = (PlanLimitReachedException) thrown;
                    assertThat(refusal.code()).isEqualTo(ErrorCode.PLAN_LIMIT_REACHED);
                    // 409, like every other guard violation here — not 402: the client branches on
                    // the code, and 402 would promise a payment path that does not exist yet.
                    assertThat(refusal.status()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void theHundredthItemIsAllowedAndTheHundredAndFirstIsRefused() {
        plan(Plan.FREE);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(99L, 100L);
        PlanLimitEnforcer enforcer = enforcer(1, 100);

        enforcer.requireRoomForAnotherItem(userId);

        assertThatThrownBy(() -> enforcer.requireRoomForAnotherItem(userId))
                .isInstanceOf(PlanLimitReachedException.class)
                .hasMessageContaining("100 active items")
                .hasMessageContaining("Archive");
    }

    @Test
    void onlyActiveItemsAreCountedTowardsTheItemLimit() {
        plan(Plan.FREE);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(4L);

        enforcer(1, 100).requireRoomForAnotherItem(userId);

        // The archived-excluding finder is the ONLY one consulted: archiving has to free room, or
        // the limit becomes a dead end instead of a wall.
        verify(itemRepository).countByUserIdAndArchivedFalse(userId);
        verifyNoMoreInteractions(itemRepository);
    }

    @Test
    void unlimitedBypassesBothLimitsWithoutEvenCounting() {
        plan(Plan.UNLIMITED);
        PlanLimitEnforcer enforcer = enforcer(1, 100);

        enforcer.requireRoomForAnotherSpace(userId);
        enforcer.requireRoomForAnotherItem(userId);

        assertThat(enforcer.hasUnlimitedEntitlement(userId)).isTrue();
        verifyNoInteractions(spaceRepository, itemRepository);
    }

    @Test
    void anAccountThatNoLongerExistsIsTreatedAsFree() {
        when(userRepository.findPlanById(userId)).thenReturn(Optional.empty());
        when(spaceRepository.countByUserId(userId)).thenReturn(1L);
        PlanLimitEnforcer enforcer = enforcer(1, 100);

        assertThat(enforcer.hasUnlimitedEntitlement(userId)).isFalse();
        // The limits apply rather than being waived: a guard's default must be the restrictive one.
        assertThatThrownBy(() -> enforcer.requireRoomForAnotherSpace(userId))
                .isInstanceOf(PlanLimitReachedException.class);
    }

    // ------------------------------------------------- the report (GET /users/me/plan)

    @Test
    void aFreeAccountReportsItsPlanItsLimitsAndItsTrueUsage() {
        plan(Plan.FREE);
        when(spaceRepository.countByUserId(userId)).thenReturn(1L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(19L);

        PlanStatusResponse status = enforcer(1, 100).statusOf(userId);

        assertThat(status.plan()).isEqualTo(Plan.FREE);
        assertThat(status.limits()).isEqualTo(new PlanLimitsResponse(1, 100));
        assertThat(status.usage()).isEqualTo(new PlanUsageResponse(1L, 19L));
    }

    @Test
    void anUnlimitedAccountReportsNoLimitsButStillReportsUsage() {
        plan(Plan.UNLIMITED);
        when(spaceRepository.countByUserId(userId)).thenReturn(4L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(19L);

        PlanStatusResponse status = enforcer(1, 100).statusOf(userId);

        assertThat(status.plan()).isEqualTo(Plan.UNLIMITED);
        // Null, not the configured numbers: an account they do not apply to must not be handed a
        // ceiling it could render. The guard says the same thing by returning before it counts.
        assertThat(status.limits()).isNull();
        // Usage is still reported — the screen shows "19 items" on both plans, so the two counts
        // run here even though the guard skips them for an UNLIMITED account.
        assertThat(status.usage()).isEqualTo(new PlanUsageResponse(4L, 19L));
    }

    @Test
    void theReportedUsageIsTheSameNumberTheGuardRefusesOn() {
        plan(Plan.FREE);
        when(spaceRepository.countByUserId(userId)).thenReturn(1L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(100L);
        PlanLimitEnforcer enforcer = enforcer(1, 100);

        PlanStatusResponse status = enforcer.statusOf(userId);

        // "usage == limit" on the screen and 409 from the guard have to be the same fact, or the
        // upgrade screen says "0 of 1 used" over a refusal.
        assertThat(status.usage().spaces()).isEqualTo(status.limits().spaces());
        assertThat(status.usage().activeItems()).isEqualTo(status.limits().items());
        assertThatThrownBy(() -> enforcer.requireRoomForAnotherSpace(userId))
                .isInstanceOf(PlanLimitReachedException.class);
        assertThatThrownBy(() -> enforcer.requireRoomForAnotherItem(userId))
                .isInstanceOf(PlanLimitReachedException.class);
    }

    @Test
    void theReportedItemUsageExcludesArchivedItems() {
        plan(Plan.FREE);
        when(spaceRepository.countByUserId(userId)).thenReturn(1L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(7L);

        assertThat(enforcer(1, 100).statusOf(userId).usage().activeItems()).isEqualTo(7L);

        // Same finder as the guard, and no other: a report that counted archived rows too would
        // disagree with the wall the moment a user archives something.
        verify(itemRepository).countByUserIdAndArchivedFalse(userId);
        verifyNoMoreInteractions(itemRepository);
    }

    @Test
    void anAccountThatNoLongerExistsIsReportedAsFreeWithItsLimits() {
        when(userRepository.findPlanById(userId)).thenReturn(Optional.empty());
        when(spaceRepository.countByUserId(userId)).thenReturn(0L);
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(0L);

        // Same restrictive default as the guard: an unknown subject is never told it is unlimited.
        assertThat(enforcer(1, 100).statusOf(userId).plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void aConfiguredLimitBelowOneIsRefusedAtStartup() {
        assertThatThrownBy(() -> new FreeTierLimits(0, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.limits.free.spaces");
        assertThatThrownBy(() -> new FreeTierLimits(1, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.limits.free.items");
    }
}
