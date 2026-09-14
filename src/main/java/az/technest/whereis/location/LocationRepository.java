package az.technest.whereis.location;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    @Query("""
            select l from Location l
            where l.id = :id
              and exists (select s.id from Space s where s.id = l.spaceId and s.userId = :userId)
            """)
    Optional<Location> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    List<Location> findAllBySpaceIdOrderByNameAsc(UUID spaceId);

    List<Location> findAllByParentLocationIdOrderByNameAsc(UUID parentLocationId);

    Optional<Location> findBySpaceIdAndParentLocationIdAndNormalizedName(
            UUID spaceId, UUID parentLocationId, String normalizedName);

    Optional<Location> findBySpaceIdAndParentLocationIdIsNullAndNormalizedName(
            UUID spaceId, String normalizedName);

    boolean existsByParentLocationId(UUID parentLocationId);

    boolean existsBySpaceId(UUID spaceId);

    /**
     * Deletes a user's entire location forest in ONE statement. The self-referencing composite FK
     * {@code fk_locations_parent_same_space} has no ON DELETE clause, i.e. NO ACTION, which
     * PostgreSQL checks at the END of the statement — so parents and children can go together
     * without any depth ordering. (RESTRICT would be checked immediately and break this.)
     * Same cross-feature subquery style as {@link #findByIdAndUserId}. No clearAutomatically — see
     * {@code ItemRepository#deleteAllByUserId}.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from Location l where l.spaceId in (select s.id from Space s where s.userId = :userId)")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
