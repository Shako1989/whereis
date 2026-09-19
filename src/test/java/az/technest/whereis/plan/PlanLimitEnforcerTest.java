package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.item.ItemRepository;
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
