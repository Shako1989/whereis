package az.technest.whereis.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import az.technest.whereis.common.error.ConflictException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.location.LocationRepository;
import az.technest.whereis.plan.PlanLimitEnforcer;
import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.PlanLimitReachedException;
import az.technest.whereis.space.dto.CreateSpaceRequest;
import az.technest.whereis.space.dto.SpaceResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SpaceServiceTest {

    @Mock
    private SpaceRepository spaceRepository;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private az.technest.whereis.location.LocationTreeDao treeDao;
    @Mock
    private PlanLimitEnforcer planLimits;
    @Spy
    private SpaceMapper mapper = new SpaceMapperImpl();
    @InjectMocks
    private SpaceService spaceService;

    private final UUID userId = UUID.randomUUID();

    @Test
    void createNormalizesNameAndScopesToUser() {
        when(spaceRepository.existsByUserIdAndNormalizedName(userId, "my home")).thenReturn(false);
        when(spaceRepository.save(any(Space.class))).thenAnswer(inv -> inv.getArgument(0));

        SpaceResponse response = spaceService.create(userId,
                new CreateSpaceRequest("  My   Home ", null, SpaceType.HOME));

        assertThat(response.name()).isEqualTo("My Home");
        verify(spaceRepository).save(any(Space.class));
    }

    @Test
    void createRejectsDuplicateNamePerUser() {
        when(spaceRepository.existsByUserIdAndNormalizedName(userId, "home")).thenReturn(true);

        assertThatThrownBy(() -> spaceService.create(userId,
                new CreateSpaceRequest("Home", null, SpaceType.HOME)))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).code()).isEqualTo(ErrorCode.DUPLICATE_NAME));
        verify(spaceRepository, never()).save(any());
    }

    @Test
    void getEnforcesOwnership() {
        UUID spaceId = UUID.randomUUID();
        when(spaceRepository.findByIdAndUserId(spaceId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spaceService.get(userId, spaceId))
                .isInstanceOf(SpaceNotFoundException.class);
    }

    @Test
    void deleteFailsWhileLocationsExist() {
        UUID spaceId = UUID.randomUUID();
        Space space = Space.builder().id(spaceId).userId(userId).name("Home")
                .normalizedName("home").type(SpaceType.HOME).build();
        when(spaceRepository.findByIdAndUserId(spaceId, userId)).thenReturn(Optional.of(space));
        when(locationRepository.existsBySpaceId(spaceId)).thenReturn(true);

        assertThatThrownBy(() -> spaceService.delete(userId, spaceId))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).code()).isEqualTo(ErrorCode.SPACE_NOT_EMPTY));
        verify(spaceRepository, never()).delete(any(Space.class));
    }

    @Test
    void lockAllSpacesOfUserTakesEveryAdvisoryLockInTheRepositorysAscendingOrder() {
        UUID lower = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higher = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(spaceRepository.findAllIdsByUserIdOrderByIdAsc(userId)).thenReturn(List.of(lower, higher));

        List<UUID> locked = spaceService.lockAllSpacesOfUser(userId);

        // Deterministic order is the deadlock guard: this is the only path holding several space locks.
        InOrder order = inOrder(treeDao);
        order.verify(treeDao).lockSpace(lower);
        order.verify(treeDao).lockSpace(higher);
        assertThat(locked).containsExactly(lower, higher);
    }

    // ----------------------------------------------------------------- free-tier space limit

    @Test
    void createRefusesAtThePlanLimitAndSavesNothing() {
        when(spaceRepository.existsByUserIdAndNormalizedName(userId, "office")).thenReturn(false);
        doThrow(PlanLimitReachedException.spaces(1, Plan.FREE, true)).when(planLimits).requireRoomForAnotherSpace(userId);

        assertThatThrownBy(() -> spaceService.create(userId,
                new CreateSpaceRequest("Office", null, SpaceType.OFFICE)))
                .isInstanceOf(PlanLimitReachedException.class)
                .hasMessageContaining("1 space");

        verify(spaceRepository, never()).save(any(Space.class));
    }

    @Test
    void aDuplicateNameIsReportedBeforeThePlanLimitIsEvenConsulted() {
        when(spaceRepository.existsByUserIdAndNormalizedName(userId, "home")).thenReturn(true);

        assertThatThrownBy(() -> spaceService.create(userId,
                new CreateSpaceRequest("Home", null, SpaceType.HOME)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");

        // Resending the name of the space you already have is better answered "it exists" than
        // "buy more" — the more specific error wins, so the guard runs after the name check.
        verifyNoInteractions(planLimits);
    }
}
