package az.technest.whereis.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.item.dto.MoveItemRequest;
import az.technest.whereis.location.Location;
import az.technest.whereis.location.LocationService;
import az.technest.whereis.location.LocationTreeDao;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.storage.FileStorageService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    @Mock
    private ItemRepository itemRepository;
    @Mock
    private ItemLocationHistoryRepository historyRepository;
    @Mock
    private LocationService locationService;
    @Mock
    private LocationTreeDao treeDao;
    @Mock
    private FileStorageService fileStorageService;

    private ItemService itemService;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        itemService = new ItemService(itemRepository, historyRepository, locationService,
                treeDao, fileStorageService, new ItemMapperImpl());
    }

    private Location location(UUID id, String name) {
        return Location.builder().id(id).spaceId(UUID.randomUUID())
                .name(name).normalizedName(name.toLowerCase()).type(LocationType.DRAWER).build();
    }

    @Test
    void moveClosesOpenHistoryOpensNewOneAndUpdatesCurrentLocation() {
        UUID itemId = UUID.randomUUID();
        UUID oldLocation = UUID.randomUUID();
        UUID newLocation = UUID.randomUUID();
        Item item = Item.builder().id(itemId).userId(userId).currentLocationId(oldLocation)
                .name("Passport").normalizedName("passport").archived(false).build();
        when(itemRepository.findForUpdate(itemId, userId)).thenReturn(Optional.of(item));
        when(itemRepository.save(any(Item.class))).thenAnswer(inv -> inv.getArgument(0));
        when(locationService.requireOwned(userId, newLocation)).thenReturn(location(newLocation, "Desk Drawer"));
        when(treeDao.resolvePaths(List.of(newLocation)))
                .thenReturn(Map.of(newLocation, List.of("Office", "Desk", "Desk Drawer")));

        ItemResponse response = itemService.moveItem(userId, itemId,
                new MoveItemRequest(newLocation, "Moved during cleanup"));

        verify(historyRepository).closeOpen(eq(itemId), any());
        ArgumentCaptor<ItemLocationHistory> captor = ArgumentCaptor.forClass(ItemLocationHistory.class);
        verify(historyRepository).save(captor.capture());
        ItemLocationHistory newRecord = captor.getValue();
        assertThat(newRecord.getRemovedAt()).isNull();
        assertThat(newRecord.getLocationId()).isEqualTo(newLocation);
        assertThat(newRecord.getLocationPathSnapshot()).isEqualTo("Office > Desk > Desk Drawer");
        assertThat(newRecord.getNote()).isEqualTo("Moved during cleanup");
        assertThat(item.getCurrentLocationId()).isEqualTo(newLocation);
        // Explicit save is mandatory: a bulk update in the same tx may have detached the entity.
        verify(itemRepository).save(item);
        assertThat(response.locationPath()).containsExactly("Office", "Desk", "Desk Drawer");
    }

    @Test
    void createWritesInitialOpenHistoryRecord() {
        UUID locationId = UUID.randomUUID();
        when(locationService.requireOwned(userId, locationId)).thenReturn(location(locationId, "Top Drawer"));
        when(itemRepository.save(any(Item.class))).thenAnswer(inv -> {
            Item saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        when(treeDao.resolvePaths(List.of(locationId)))
                .thenReturn(Map.of(locationId, List.of("Home", "Bedroom", "Top Drawer")));

        ItemResponse response = itemService.createAt(userId, locationId, " Passport ", null, null, null);

        assertThat(response.name()).isEqualTo("Passport");
        ArgumentCaptor<ItemLocationHistory> captor = ArgumentCaptor.forClass(ItemLocationHistory.class);
        verify(historyRepository).save(captor.capture());
        assertThat(captor.getValue().getRemovedAt()).isNull();
        assertThat(captor.getValue().getLocationPathSnapshot()).isEqualTo("Home > Bedroom > Top Drawer");
    }

    @Test
    void listWhitelistsSortAndClampsPageSize() {
        when(itemRepository.findAllByUserIdAndArchivedFalse(eq(userId), any(Pageable.class)))
                .thenReturn(Page.empty());

        itemService.list(userId, 0, 500, "passwordHash,desc", false);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(itemRepository).findAllByUserIdAndArchivedFalse(eq(userId), captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageSize()).isEqualTo(ItemService.MAX_PAGE_SIZE);
        assertThat(pageable.getSort().getOrderFor("updatedAt"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getSort().getOrderFor("passwordHash")).isNull();
    }

    @Test
    void listResolvesPathsInOneBatch() {
        UUID locationId = UUID.randomUUID();
        Item item = Item.builder().id(UUID.randomUUID()).userId(userId).currentLocationId(locationId)
                .name("Keys").normalizedName("keys").archived(false).build();
        when(itemRepository.findAllByUserIdAndArchivedFalse(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(item, item)));
        when(treeDao.resolvePaths(List.of(locationId)))
                .thenReturn(Map.of(locationId, List.of("Home", "Hallway")));

        Page<ItemResponse> page = itemService.list(userId, 0, 20, null, false);

        assertThat(page.getContent()).allSatisfy(r ->
                assertThat(r.locationPath()).containsExactly("Home", "Hallway"));
        verify(treeDao).resolvePaths(List.of(locationId));
    }

    @Test
    void deleteEnqueuesFileCleanupBeforeRemovingTheItem() {
        UUID itemId = UUID.randomUUID();
        Item item = Item.builder().id(itemId).userId(userId).currentLocationId(UUID.randomUUID())
                .name("Keys").normalizedName("keys").archived(false).build();
        when(itemRepository.findForUpdate(itemId, userId)).thenReturn(Optional.of(item));

        itemService.delete(userId, itemId);

        verify(fileStorageService).enqueueAllForItem(itemId);
        verify(itemRepository).delete(item);
    }
}
