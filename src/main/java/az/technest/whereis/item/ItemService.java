package az.technest.whereis.item;

import az.technest.whereis.common.util.Names;
import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemHistoryResponse;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.item.dto.MoveItemRequest;
import az.technest.whereis.item.dto.UpdateItemRequest;
import az.technest.whereis.location.Location;
import az.technest.whereis.location.LocationService;
import az.technest.whereis.location.LocationTreeDao;
import az.technest.whereis.storage.FileStorageService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ItemService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORTABLE = Set.of("name", "category", "createdAt", "updatedAt");
    private static final String PATH_SEPARATOR = " > ";

    private final ItemRepository itemRepository;
    private final ItemLocationHistoryRepository historyRepository;
    private final LocationService locationService;
    private final LocationTreeDao treeDao;
    private final FileStorageService fileStorageService;
    private final ItemMapper mapper;

    @Transactional
    public ItemResponse create(UUID userId, CreateItemRequest request) {
        return createAt(userId, request.locationId(), request.name(), request.description(),
                request.category(), null);
    }

    /**
     * Shared creation path for the REST API and the AI assistant.
     * Writes the item and its initial open history record in one transaction.
     */
    @Transactional
    public ItemResponse createAt(UUID userId, UUID locationId, String name, String description,
                                 String category, String note) {
        Location location = locationService.requireOwned(userId, locationId);
        String cleanName = Names.clean(name);
        Item item = itemRepository.save(Item.builder()
                .userId(userId)
                .currentLocationId(location.getId())
                .name(cleanName)
                .normalizedName(Names.normalize(cleanName))
                .description(Names.clean(description))
                .category(Names.clean(category))
                .archived(false)
                .build());
        List<String> path = pathOf(location.getId());
        historyRepository.save(ItemLocationHistory.builder()
                .itemId(item.getId())
                .locationId(location.getId())
                .locationPathSnapshot(String.join(PATH_SEPARATOR, path))
                .note(note)
                .placedAt(Instant.now())
                .build());
        return mapper.toResponse(item, path);
    }

    @Transactional(readOnly = true)
    public ItemResponse get(UUID userId, UUID itemId) {
        Item item = requireOwned(userId, itemId);
        return mapper.toResponse(item, pathOf(item.getCurrentLocationId()));
    }

    @Transactional(readOnly = true)
    public Page<ItemResponse> list(UUID userId, int page, int size, String sort, boolean includeArchived) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), parseSort(sort));
        Page<Item> items = includeArchived
                ? itemRepository.findAllByUserId(userId, pageable)
                : itemRepository.findAllByUserIdAndArchivedFalse(userId, pageable);
        // One batch query resolves the location paths for the whole page — no per-row lookups.
        Map<UUID, List<String>> paths = treeDao.resolvePaths(
                items.getContent().stream().map(Item::getCurrentLocationId).distinct().toList());
        return items.map(item -> mapper.toResponse(item, paths.getOrDefault(item.getCurrentLocationId(), List.of())));
    }

    @Transactional
    public ItemResponse update(UUID userId, UUID itemId, UpdateItemRequest request) {
        Item item = requireOwned(userId, itemId);
        String cleanName = Names.clean(request.name());
        item.setName(cleanName);
        item.setNormalizedName(Names.normalize(cleanName));
        item.setDescription(Names.clean(request.description()));
        item.setCategory(Names.clean(request.category()));
        if (request.archived() != null) {
            item.setArchived(request.archived());
        }
        return mapper.toResponse(item, pathOf(item.getCurrentLocationId()));
    }

    /**
     * Atomic move: lock the item row, verify ownership of both sides, close the open
     * history record, open a new one, update the current location.
     */
    @Transactional
    public ItemResponse moveItem(UUID userId, UUID itemId, MoveItemRequest request) {
        Item item = itemRepository.findForUpdate(itemId, userId).orElseThrow(ItemNotFoundException::new);
        Location target = locationService.requireOwned(userId, request.locationId());
        Instant now = Instant.now();
        historyRepository.closeOpen(item.getId(), now);
        List<String> path = pathOf(target.getId());
        historyRepository.save(ItemLocationHistory.builder()
                .itemId(item.getId())
                .locationId(target.getId())
                .locationPathSnapshot(String.join(PATH_SEPARATOR, path))
                .note(Names.clean(request.note()))
                .placedAt(now)
                .build());
        item.setCurrentLocationId(target.getId());
        // Explicit save: belt-and-braces against detachment by bulk updates in this
        // transaction — dirty checking alone must not be the only path to this UPDATE.
        Item saved = itemRepository.save(item);
        return mapper.toResponse(saved, path);
    }

    @Transactional(readOnly = true)
    public List<ItemHistoryResponse> history(UUID userId, UUID itemId) {
        requireOwned(userId, itemId);
        return historyRepository.findAllByItemIdOrderByPlacedAtDesc(itemId).stream()
                .map(mapper::toHistoryResponse)
                .toList();
    }

    @Transactional
    public void delete(UUID userId, UUID itemId) {
        // Row lock narrows the race with a concurrent photo upload committing between the
        // outbox snapshot below and the cascade delete (which would leak the MinIO object).
        Item item = itemRepository.findForUpdate(itemId, userId).orElseThrow(ItemNotFoundException::new);
        // Enqueue MinIO deletions before the cascade removes the metadata rows.
        fileStorageService.enqueueAllForItem(itemId);
        itemRepository.delete(item);
    }

    Item requireOwned(UUID userId, UUID itemId) {
        return itemRepository.findByIdAndUserId(itemId, userId).orElseThrow(ItemNotFoundException::new);
    }

    private List<String> pathOf(UUID locationId) {
        return treeDao.resolvePaths(List.of(locationId)).getOrDefault(locationId, List.of());
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "updatedAt");
        }
        String[] parts = sort.split(",");
        String property = parts[0].trim();
        if (!SORTABLE.contains(property)) {
            property = "updatedAt";
        }
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, property);
    }
}
