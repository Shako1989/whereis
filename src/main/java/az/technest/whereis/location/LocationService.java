package az.technest.whereis.location;

import az.technest.whereis.common.error.ConflictException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.util.Names;
import az.technest.whereis.item.ItemRepository;
import az.technest.whereis.location.dto.CreateLocationRequest;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.location.dto.LocationTreeNode;
import az.technest.whereis.location.dto.UpdateLocationRequest;
import az.technest.whereis.space.SpaceRepository;
import az.technest.whereis.space.SpaceNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LocationService {

    /** Sanity bound protecting the recursive CTEs; the manual API is otherwise unlimited-depth. */
    static final int MAX_DEPTH = 32;

    private final LocationRepository locationRepository;
    private final SpaceRepository spaceRepository;
    private final ItemRepository itemRepository;
    private final LocationTreeDao treeDao;
    private final LocationMapper mapper;

    @Transactional
    public LocationResponse create(UUID userId, UUID spaceId, CreateLocationRequest request) {
        requireOwnedSpace(userId, spaceId);
        treeDao.lockSpace(spaceId);
        UUID parentId = request.parentLocationId();
        if (parentId != null) {
            Location parent = requireOwned(userId, parentId);
            requireSameSpace(parent, spaceId);
            requireDepthBelowLimit(parent.getId());
        }
        String name = Names.clean(request.name());
        String normalizedName = Names.normalize(name);
        requireSiblingNameFree(spaceId, parentId, normalizedName);
        Location location = Location.builder()
                .spaceId(spaceId)
                .parentLocationId(parentId)
                .name(name)
                .normalizedName(normalizedName)
                .description(Names.clean(request.description()))
                .type(request.type())
                .build();
        return mapper.toResponse(locationRepository.save(location));
    }

    @Transactional(readOnly = true)
    public List<LocationResponse> listBySpace(UUID userId, UUID spaceId) {
        requireOwnedSpace(userId, spaceId);
        return locationRepository.findAllBySpaceIdOrderByNameAsc(spaceId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public LocationResponse get(UUID userId, UUID locationId) {
        return mapper.toResponse(requireOwned(userId, locationId));
    }

    @Transactional(readOnly = true)
    public List<LocationResponse> children(UUID userId, UUID locationId) {
        Location location = requireOwned(userId, locationId);
        return locationRepository.findAllByParentLocationIdOrderByNameAsc(location.getId()).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LocationTreeNode> tree(UUID userId, UUID spaceId) {
        requireOwnedSpace(userId, spaceId);
        List<Location> all = locationRepository.findAllBySpaceIdOrderByNameAsc(spaceId);
        Map<UUID, List<Location>> byParent = new HashMap<>();
        for (Location location : all) {
            byParent.computeIfAbsent(location.getParentLocationId(), k -> new ArrayList<>()).add(location);
        }
        return buildNodes(byParent.get(null), byParent);
    }

    @Transactional
    public LocationResponse update(UUID userId, UUID locationId, UpdateLocationRequest request) {
        Location location = requireOwned(userId, locationId);
        treeDao.lockSpace(location.getSpaceId());
        UUID newParentId = request.parentLocationId();
        if (!Objects.equals(newParentId, location.getParentLocationId()) && newParentId != null) {
            Location newParent = requireOwned(userId, newParentId);
            requireSameSpace(newParent, location.getSpaceId());
            if (treeDao.selfAndAncestors(newParent.getId()).contains(locationId)) {
                throw new CycleDetectedException();
            }
            requireDepthBelowLimit(newParent.getId());
        }
        String name = Names.clean(request.name());
        String normalizedName = Names.normalize(name);
        boolean keyChanged = !normalizedName.equals(location.getNormalizedName())
                || !Objects.equals(newParentId, location.getParentLocationId());
        if (keyChanged) {
            requireSiblingNameFree(location.getSpaceId(), newParentId, normalizedName);
        }
        location.setName(name);
        location.setNormalizedName(normalizedName);
        location.setDescription(Names.clean(request.description()));
        location.setType(request.type());
        location.setParentLocationId(newParentId);
        return mapper.toResponse(location);
    }

    @Transactional
    public void delete(UUID userId, UUID locationId) {
        Location location = requireOwned(userId, locationId);
        treeDao.lockSpace(location.getSpaceId());
        if (locationRepository.existsByParentLocationId(locationId)) {
            throw new LocationNotEmptyException("Location still has child locations");
        }
        if (itemRepository.existsByCurrentLocationId(locationId)) {
            throw new LocationNotEmptyException("Items are still stored in this location");
        }
        locationRepository.delete(location);
    }

    /**
     * Resolves an ordered chain of location names under a space, creating missing segments.
     * Used by the AI assistant after interpretation has been validated — never directly from AI output.
     */
    @Transactional
    public ChainResult resolveOrCreateChain(UUID userId, UUID spaceId, List<ChainSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            throw new InvalidLocationHierarchyException("Location chain is empty");
        }
        requireOwnedSpace(userId, spaceId);
        treeDao.lockSpace(spaceId);
        Location parent = null;
        List<String> created = new ArrayList<>();
        for (ChainSegment segment : segments) {
            String name = Names.clean(segment.name());
            String normalizedName = Names.normalize(name);
            UUID parentId = parent == null ? null : parent.getId();
            Optional<Location> existing = findSibling(spaceId, parentId, normalizedName);
            Location current;
            if (existing.isPresent()) {
                current = existing.get();
            } else {
                try {
                    current = locationRepository.save(Location.builder()
                            .spaceId(spaceId)
                            .parentLocationId(parentId)
                            .name(name)
                            .normalizedName(normalizedName)
                            .type(segment.type())
                            .build());
                    created.add(name);
                } catch (DataIntegrityViolationException e) {
                    // Concurrent creation of the same segment; the advisory lock makes this
                    // unlikely, the unique constraint makes it safe. Re-read once.
                    current = findSibling(spaceId, parentId, normalizedName)
                            .orElseThrow(() -> e);
                }
            }
            parent = current;
        }
        return new ChainResult(parent, List.copyOf(created));
    }

    private Optional<Location> findSibling(UUID spaceId, UUID parentId, String normalizedName) {
        return parentId == null
                ? locationRepository.findBySpaceIdAndParentLocationIdIsNullAndNormalizedName(spaceId, normalizedName)
                : locationRepository.findBySpaceIdAndParentLocationIdAndNormalizedName(spaceId, parentId, normalizedName);
    }

    private List<LocationTreeNode> buildNodes(List<Location> locations, Map<UUID, List<Location>> byParent) {
        if (locations == null) {
            return List.of();
        }
        return locations.stream()
                .map(location -> new LocationTreeNode(
                        location.getId(),
                        location.getName(),
                        location.getType(),
                        buildNodes(byParent.get(location.getId()), byParent)))
                .toList();
    }

    private void requireOwnedSpace(UUID userId, UUID spaceId) {
        spaceRepository.findByIdAndUserId(spaceId, userId).orElseThrow(SpaceNotFoundException::new);
    }

    /** Ownership-scoped lookup used across features (items, files, assistant). */
    public Location requireOwned(UUID userId, UUID locationId) {
        return locationRepository.findByIdAndUserId(locationId, userId).orElseThrow(LocationNotFoundException::new);
    }

    private void requireSameSpace(Location parent, UUID spaceId) {
        if (!parent.getSpaceId().equals(spaceId)) {
            throw new InvalidLocationHierarchyException("Parent location belongs to a different space");
        }
    }

    private void requireDepthBelowLimit(UUID parentId) {
        if (treeDao.selfAndAncestors(parentId).size() >= MAX_DEPTH) {
            throw new InvalidLocationHierarchyException("Maximum location depth exceeded");
        }
    }

    private void requireSiblingNameFree(UUID spaceId, UUID parentId, String normalizedName) {
        if (findSibling(spaceId, parentId, normalizedName).isPresent()) {
            throw new ConflictException(ErrorCode.DUPLICATE_NAME,
                    "A location with this name already exists at this level");
        }
    }
}
