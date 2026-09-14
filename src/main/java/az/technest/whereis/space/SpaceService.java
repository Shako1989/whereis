package az.technest.whereis.space;

import az.technest.whereis.common.error.ConflictException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.util.Names;
import az.technest.whereis.location.LocationRepository;
import az.technest.whereis.location.LocationTreeDao;
import az.technest.whereis.space.dto.CreateSpaceRequest;
import az.technest.whereis.space.dto.SpaceResponse;
import az.technest.whereis.space.dto.UpdateSpaceRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SpaceService {

    private final SpaceRepository spaceRepository;
    private final LocationRepository locationRepository;
    private final LocationTreeDao treeDao;
    private final SpaceMapper mapper;

    @Transactional
    public SpaceResponse create(UUID userId, CreateSpaceRequest request) {
        String name = Names.clean(request.name());
        String normalizedName = Names.normalize(name);
        if (spaceRepository.existsByUserIdAndNormalizedName(userId, normalizedName)) {
            throw new ConflictException(ErrorCode.DUPLICATE_NAME, "A space with this name already exists");
        }
        Space space = Space.builder()
                .userId(userId)
                .name(name)
                .normalizedName(normalizedName)
                .description(Names.clean(request.description()))
                .type(request.type())
                .build();
        return mapper.toResponse(spaceRepository.save(space));
    }

    @Transactional(readOnly = true)
    public List<SpaceResponse> list(UUID userId) {
        return spaceRepository.findAllByUserIdOrderByNameAsc(userId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SpaceResponse get(UUID userId, UUID spaceId) {
        return mapper.toResponse(requireOwned(userId, spaceId));
    }

    @Transactional
    public SpaceResponse update(UUID userId, UUID spaceId, UpdateSpaceRequest request) {
        Space space = requireOwned(userId, spaceId);
        String name = Names.clean(request.name());
        String normalizedName = Names.normalize(name);
        if (!normalizedName.equals(space.getNormalizedName())
                && spaceRepository.existsByUserIdAndNormalizedName(userId, normalizedName)) {
            throw new ConflictException(ErrorCode.DUPLICATE_NAME, "A space with this name already exists");
        }
        space.setName(name);
        space.setNormalizedName(normalizedName);
        space.setDescription(Names.clean(request.description()));
        space.setType(request.type());
        return mapper.toResponse(space);
    }

    @Transactional
    public void delete(UUID userId, UUID spaceId) {
        Space space = requireOwned(userId, spaceId);
        // Same advisory lock as all structural location writers: serializes this
        // check-then-delete with concurrent location creation in the space.
        treeDao.lockSpace(spaceId);
        if (locationRepository.existsBySpaceId(spaceId)) {
            throw new ConflictException(ErrorCode.SPACE_NOT_EMPTY,
                    "Space still contains locations; delete or move them first");
        }
        spaceRepository.delete(space);
    }

    /**
     * Account deletion, step one: take the same transaction-scoped advisory lock every structural
     * location writer takes, for every space of the user, in ascending id order. This is the only
     * code path that ever holds more than one space lock at once; the fixed order rules out a
     * deadlock even if two users' keys collide under {@code hashtextextended}. MANDATORY because
     * a {@code pg_advisory_xact_lock} outside a transaction is released immediately.
     *
     * @return the locked space ids, ascending
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> lockAllSpacesOfUser(UUID userId) {
        List<UUID> spaceIds = spaceRepository.findAllIdsByUserIdOrderByIdAsc(userId);
        spaceIds.forEach(treeDao::lockSpace);
        return spaceIds;
    }

    /**
     * Account deletion: all spaces of the user in one statement. MANDATORY: the caller must have
     * emptied the location trees first — the FK from locations is the DB form of SPACE_NOT_EMPTY.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteAllForUser(UUID userId) {
        return spaceRepository.deleteAllByUserId(userId);
    }

    Space requireOwned(UUID userId, UUID spaceId) {
        return spaceRepository.findByIdAndUserId(spaceId, userId).orElseThrow(SpaceNotFoundException::new);
    }
}
