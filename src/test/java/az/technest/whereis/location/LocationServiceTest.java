package az.technest.whereis.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import az.technest.whereis.item.ItemRepository;
import az.technest.whereis.location.dto.UpdateLocationRequest;
import az.technest.whereis.space.Space;
import az.technest.whereis.space.SpaceRepository;
import az.technest.whereis.space.SpaceType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class LocationServiceTest {

    @Mock
    private LocationRepository locationRepository;
    @Mock
    private SpaceRepository spaceRepository;
    @Mock
    private ItemRepository itemRepository;
    @Mock
    private LocationTreeDao treeDao;

    private LocationService locationService;

    private final UUID userId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        locationService = new LocationService(
                locationRepository, spaceRepository, itemRepository, treeDao, new LocationMapperImpl());
    }

    private Space ownedSpace() {
        return Space.builder().id(spaceId).userId(userId).name("Home")
                .normalizedName("home").type(SpaceType.HOME).build();
    }

    private Location location(UUID id, UUID parentId, String name) {
        return Location.builder().id(id).spaceId(spaceId).parentLocationId(parentId)
                .name(name).normalizedName(name.toLowerCase()).type(LocationType.OTHER).build();
    }

    @Test
    void reparentingUnderOwnDescendantIsRejectedAsCycle() {
        UUID locationId = UUID.randomUUID();
        UUID descendantId = UUID.randomUUID();
        Location moved = location(locationId, null, "Drawer");
        Location descendant = location(descendantId, locationId, "Box");
        when(locationRepository.findByIdAndUserId(locationId, userId)).thenReturn(Optional.of(moved));
        when(locationRepository.findByIdAndUserId(descendantId, userId)).thenReturn(Optional.of(descendant));
        // Walking up from the proposed parent reaches the moved location itself.
        when(treeDao.selfAndAncestors(descendantId)).thenReturn(List.of(descendantId, locationId));

        assertThatThrownBy(() -> locationService.update(userId, locationId,
                new UpdateLocationRequest("Drawer", null, LocationType.DRAWER, descendantId)))
                .isInstanceOf(CycleDetectedException.class);
    }

    @Test
    void parentFromDifferentSpaceIsRejected() {
        UUID parentId = UUID.randomUUID();
        Location foreignParent = Location.builder().id(parentId).spaceId(UUID.randomUUID())
                .name("Elsewhere").normalizedName("elsewhere").type(LocationType.ROOM).build();
        when(spaceRepository.findByIdAndUserId(spaceId, userId)).thenReturn(Optional.of(ownedSpace()));
        when(locationRepository.findByIdAndUserId(parentId, userId)).thenReturn(Optional.of(foreignParent));

        assertThatThrownBy(() -> locationService.create(userId, spaceId,
                new az.technest.whereis.location.dto.CreateLocationRequest("Shelf", null, LocationType.SHELF, parentId)))
                .isInstanceOf(InvalidLocationHierarchyException.class);
    }

    @Test
    void deleteIsBlockedByChildren() {
        UUID locationId = UUID.randomUUID();
        when(locationRepository.findByIdAndUserId(locationId, userId))
                .thenReturn(Optional.of(location(locationId, null, "Wardrobe")));
        when(locationRepository.existsByParentLocationId(locationId)).thenReturn(true);

        assertThatThrownBy(() -> locationService.delete(userId, locationId))
                .isInstanceOf(LocationNotEmptyException.class);
        verify(locationRepository, never()).delete(any(Location.class));
    }

    @Test
    void deleteIsBlockedByStoredItems() {
        UUID locationId = UUID.randomUUID();
        when(locationRepository.findByIdAndUserId(locationId, userId))
                .thenReturn(Optional.of(location(locationId, null, "Drawer")));
        when(locationRepository.existsByParentLocationId(locationId)).thenReturn(false);
        when(itemRepository.existsByCurrentLocationId(locationId)).thenReturn(true);

        assertThatThrownBy(() -> locationService.delete(userId, locationId))
                .isInstanceOf(LocationNotEmptyException.class);
        verify(locationRepository, never()).delete(any(Location.class));
    }

    @Test
    void resolveOrCreateChainReusesExistingSegmentsAndCreatesMissingOnes() {
        Location bedroom = location(UUID.randomUUID(), null, "Bedroom");
        when(spaceRepository.findByIdAndUserId(spaceId, userId)).thenReturn(Optional.of(ownedSpace()));
        when(locationRepository.findBySpaceIdAndParentLocationIdIsNullAndNormalizedName(spaceId, "bedroom"))
                .thenReturn(Optional.of(bedroom));
        when(locationRepository.findBySpaceIdAndParentLocationIdAndNormalizedName(spaceId, bedroom.getId(), "wardrobe"))
                .thenReturn(Optional.empty());
        when(locationRepository.save(any(Location.class)))
                .thenAnswer(inv -> {
                    Location saved = inv.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return saved;
                });

        ChainResult result = locationService.resolveOrCreateChain(userId, spaceId, List.of(
                new ChainSegment("Bedroom", LocationType.ROOM),
                new ChainSegment("Wardrobe", LocationType.FURNITURE)));

        assertThat(result.createdNames()).containsExactly("Wardrobe");
        assertThat(result.leaf().getName()).isEqualTo("Wardrobe");
        assertThat(result.leaf().getParentLocationId()).isEqualTo(bedroom.getId());
    }

    @Test
    void resolveOrCreateChainSurvivesConcurrentCreation() {
        Location existing = location(UUID.randomUUID(), null, "Bedroom");
        when(spaceRepository.findByIdAndUserId(spaceId, userId)).thenReturn(Optional.of(ownedSpace()));
        when(locationRepository.findBySpaceIdAndParentLocationIdIsNullAndNormalizedName(spaceId, "bedroom"))
                .thenReturn(Optional.empty())        // first lookup: not there
                .thenReturn(Optional.of(existing));  // re-read after constraint violation
        when(locationRepository.save(any(Location.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        ChainResult result = locationService.resolveOrCreateChain(userId, spaceId,
                List.of(new ChainSegment("Bedroom", LocationType.ROOM)));

        assertThat(result.leaf().getId()).isEqualTo(existing.getId());
        assertThat(result.createdNames()).isEmpty();
    }

    @Test
    void emptyChainIsRejected() {
        assertThatThrownBy(() -> locationService.resolveOrCreateChain(userId, spaceId, List.of()))
                .isInstanceOf(InvalidLocationHierarchyException.class);
    }
}
