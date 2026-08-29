package az.technest.whereis.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import az.technest.whereis.common.error.ConflictException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.location.LocationRepository;
import az.technest.whereis.space.dto.CreateSpaceRequest;
import az.technest.whereis.space.dto.SpaceResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
}
