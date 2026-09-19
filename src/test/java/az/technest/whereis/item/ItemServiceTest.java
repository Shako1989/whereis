package az.technest.whereis.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.item.dto.MoveItemRequest;
import az.technest.whereis.location.Location;
import az.technest.whereis.location.LocationNotFoundException;
import az.technest.whereis.location.LocationService;
import az.technest.whereis.location.LocationTreeDao;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.plan.PlanLimitEnforcer;
import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.PlanLimitReachedException;
import az.technest.whereis.storage.FileStorageService;
import az.technest.whereis.storage.dto.ItemPrimaryImage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
    @Mock
    private PlanLimitEnforcer planLimits;

    private ItemService itemService;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        itemService = new ItemService(itemRepository, historyRepository, locationService,
                treeDao, fileStorageService, planLimits, new ItemMapperImpl());
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

        itemService.list(userId, null, 0, 500, "passwordHash,desc", false);

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

        Page<ItemResponse> page = itemService.list(userId, null, 0, 20, null, false);

        assertThat(page.getContent()).allSatisfy(r ->
                assertThat(r.locationPath()).containsExactly("Home", "Hallway"));
        verify(treeDao).resolvePaths(List.of(locationId));
    }

    @Test
    void listResolvesCoverPhotosInOneBatchForTheWholePage() {
        UUID locationId = UUID.randomUUID();
        UUID withPhoto = UUID.randomUUID();
        UUID withoutPhoto = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        Item first = Item.builder().id(withPhoto).userId(userId).currentLocationId(locationId)
                .name("Keys").normalizedName("keys").archived(false).build();
        Item second = Item.builder().id(withoutPhoto).userId(userId).currentLocationId(locationId)
                .name("Passport").normalizedName("passport").archived(false).build();
        when(itemRepository.findAllByUserIdAndArchivedFalse(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first, second)));
        when(treeDao.resolvePaths(List.of(locationId))).thenReturn(Map.of(locationId, List.of("Home")));
        when(fileStorageService.primaryImages(List.of(withPhoto, withoutPhoto)))
                .thenReturn(Map.of(withPhoto, new ItemPrimaryImage(fileId, "https://minio/presigned")));

        List<ItemResponse> content = itemService.list(userId, null, 0, 20, null, false).getContent();

        assertThat(content.get(0).primaryFileId()).isEqualTo(fileId);
        assertThat(content.get(0).primaryImageUrl()).isEqualTo("https://minio/presigned");
        assertThat(content.get(1).primaryFileId()).isNull();
        assertThat(content.get(1).primaryImageUrl()).isNull();
        // Exactly one cover lookup for the page: the whole point of BR-3 is killing the N+1.
        verify(fileStorageService).primaryImages(List.of(withPhoto, withoutPhoto));
        verifyNoMoreInteractions(fileStorageService);
    }

    @Test
    void listWithoutALocationNeverTouchesLocationService() {
        when(itemRepository.findAllByUserIdAndArchivedFalse(eq(userId), any(Pageable.class)))
                .thenReturn(Page.empty());

        itemService.list(userId, null, 0, 20, null, false);

        // An absent locationId must stay the pre-filter behaviour exactly: no ownership lookup,
        // no location-scoped finder, one statement less than the filtered path.
        verifyNoInteractions(locationService);
        verify(itemRepository).findAllByUserIdAndArchivedFalse(eq(userId), any(Pageable.class));
        verifyNoMoreInteractions(itemRepository);
    }

    @Test
    void listAtALocationVerifiesOwnershipAndUsesTheUserScopedFinder() {
        UUID locationId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Item item = Item.builder().id(itemId).userId(userId).currentLocationId(locationId)
                .name("Keys").normalizedName("keys").archived(false).build();
        when(locationService.requireOwned(userId, locationId)).thenReturn(location(locationId, "Top Drawer"));
        when(itemRepository.findAllByUserIdAndCurrentLocationIdAndArchivedFalse(
                eq(userId), eq(locationId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(item)));
        when(treeDao.resolvePaths(List.of(locationId)))
                .thenReturn(Map.of(locationId, List.of("Home", "Bedroom", "Top Drawer")));

        Page<ItemResponse> page = itemService.list(userId, locationId, 0, 20, null, false);

        assertThat(page.getContent()).singleElement()
                .satisfies(r -> assertThat(r.currentLocationId()).isEqualTo(locationId));
        // Ownership of the LOCATION is a separate check from the userId scoping of the items;
        // dropping it would turn a foreign id into an empty page instead of a 404.
        verify(locationService).requireOwned(userId, locationId);
        // §6: no bare findAllByCurrentLocationId exists, so the userId travels into the query too.
        verify(itemRepository).findAllByUserIdAndCurrentLocationIdAndArchivedFalse(
                eq(userId), eq(locationId), any(Pageable.class));
        verifyNoMoreInteractions(itemRepository);
        // Still one batch query each, exactly as on the unfiltered path.
        verify(treeDao).resolvePaths(List.of(locationId));
        verify(fileStorageService).primaryImages(List.of(itemId));
        verifyNoMoreInteractions(treeDao, fileStorageService);
    }

    @Test
    void listAtALocationThatIsNotTheCallersIs404AndReadsNoItems() {
        UUID foreign = UUID.randomUUID();
        when(locationService.requireOwned(userId, foreign)).thenThrow(new LocationNotFoundException());

        assertThatThrownBy(() -> itemService.list(userId, foreign, 0, 20, null, false))
                .isInstanceOf(LocationNotFoundException.class);

        // Nothing is read, so nothing can leak — not even the fact that the id exists.
        verifyNoInteractions(itemRepository, treeDao, fileStorageService);
    }

    @Test
    void listAtALocationStillWhitelistsSortAndClampsPageSize() {
        UUID locationId = UUID.randomUUID();
        when(locationService.requireOwned(userId, locationId)).thenReturn(location(locationId, "Top Drawer"));
        when(itemRepository.findAllByUserIdAndCurrentLocationIdAndArchivedFalse(
                eq(userId), eq(locationId), any(Pageable.class)))
                .thenReturn(Page.empty());

        itemService.list(userId, locationId, 0, 500, "passwordHash,desc", false);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(itemRepository).findAllByUserIdAndCurrentLocationIdAndArchivedFalse(
                eq(userId), eq(locationId), captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageSize()).isEqualTo(ItemService.MAX_PAGE_SIZE);
        assertThat(pageable.getSort().getOrderFor("updatedAt"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getSort().getOrderFor("passwordHash")).isNull();
    }

    @Test
    void listAtALocationHonoursIncludeArchivedOnBothSettings() {
        UUID locationId = UUID.randomUUID();
        when(locationService.requireOwned(userId, locationId)).thenReturn(location(locationId, "Top Drawer"));
        when(itemRepository.findAllByUserIdAndCurrentLocationIdAndArchivedFalse(
                eq(userId), eq(locationId), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(itemRepository.findAllByUserIdAndCurrentLocationId(
                eq(userId), eq(locationId), any(Pageable.class)))
                .thenReturn(Page.empty());

        itemService.list(userId, locationId, 0, 20, null, false);
        itemService.list(userId, locationId, 0, 20, null, true);

        // The archived split is orthogonal to the filter: each flag picks its own scoped finder.
        verify(itemRepository).findAllByUserIdAndCurrentLocationIdAndArchivedFalse(
                eq(userId), eq(locationId), any(Pageable.class));
        verify(itemRepository).findAllByUserIdAndCurrentLocationId(
                eq(userId), eq(locationId), any(Pageable.class));
        verifyNoMoreInteractions(itemRepository);
    }

    @Test
    void createDoesNotLookUpACoverPhotoForABrandNewItem() {
        UUID locationId = UUID.randomUUID();
        when(locationService.requireOwned(userId, locationId)).thenReturn(location(locationId, "Top Drawer"));
        when(itemRepository.save(any(Item.class))).thenAnswer(inv -> {
            Item saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        when(treeDao.resolvePaths(List.of(locationId))).thenReturn(Map.of(locationId, List.of("Home")));

        ItemResponse response = itemService.createAt(userId, locationId, "Passport", null, null, null);

        assertThat(response.primaryFileId()).isNull();
        assertThat(response.primaryImageUrl()).isNull();
        verifyNoInteractions(fileStorageService);
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

    @Test
    void deleteAllForUserLocksThenEnqueuesThenDeletesAndReportsBothCounts() {
        when(itemRepository.lockAllForUser(userId)).thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));
        when(fileStorageService.enqueueAllForUser(userId)).thenReturn(5);
        when(itemRepository.deleteAllByUserId(userId)).thenReturn(2);

        ItemDeletionSummary summary = itemService.deleteAllForUser(userId);

        // The order IS the invariant: item_files cascades from items, so the outbox snapshot must
        // precede the delete or the MinIO objects are orphaned; the lock must precede the snapshot
        // or a concurrent upload can slip its metadata in between. Swapping any two fails this.
        InOrder order = inOrder(itemRepository, fileStorageService);
        order.verify(itemRepository).lockAllForUser(userId);
        order.verify(fileStorageService).enqueueAllForUser(userId);
        order.verify(itemRepository).deleteAllByUserId(userId);
        assertThat(summary.items()).isEqualTo(2);
        assertThat(summary.filesEnqueued()).isEqualTo(5);
        verifyNoMoreInteractions(fileStorageService);
    }

    // ------------------------------------------------------------------ free-tier item limit

    @Test
    void createAtRefusesAtThePlanLimitAndWritesNothing() {
        UUID locationId = UUID.randomUUID();
        when(locationService.requireOwned(userId, locationId)).thenReturn(location(locationId, "Top Drawer"));
        doThrow(PlanLimitReachedException.activeItems(100, Plan.FREE, true)).when(planLimits).requireRoomForAnotherItem(userId);

        assertThatThrownBy(() -> itemService.createAt(userId, locationId, "Passport", null, null, null))
                .isInstanceOf(PlanLimitReachedException.class)
                .hasMessageContaining("100 active items");

        // Neither the item nor its open history record exists — the refusal is before every write,
        // so the assistant's chain path has nothing to roll back either.
        verifyNoInteractions(itemRepository, historyRepository);
    }

    @Test
    void theManualCreateEndpointGoesThroughTheSameGuard() {
        UUID locationId = UUID.randomUUID();
        when(locationService.requireOwned(userId, locationId)).thenReturn(location(locationId, "Top Drawer"));
        doThrow(PlanLimitReachedException.activeItems(100, Plan.FREE, true)).when(planLimits).requireRoomForAnotherItem(userId);

        // create() delegates to createAt(), which is why one guard covers all three creation paths.
        assertThatThrownBy(() -> itemService.create(userId,
                new CreateItemRequest("Passport", null, null, locationId)))
                .isInstanceOf(PlanLimitReachedException.class);
        verifyNoInteractions(itemRepository);
    }

    @Test
    void aForeignLocationIsStill404EvenWhenThePlanHasNoRoomLeft() {
        UUID locationId = UUID.randomUUID();
        when(locationService.requireOwned(userId, locationId)).thenThrow(new LocationNotFoundException());

        assertThatThrownBy(() -> itemService.createAt(userId, locationId, "Passport", null, null, null))
                .isInstanceOf(LocationNotFoundException.class);

        // Ownership is decided first on purpose: a 409 here would answer a request whose location
        // id is not even the caller's, and 404 is the established contract for that.
        verifyNoInteractions(planLimits);
    }
}
