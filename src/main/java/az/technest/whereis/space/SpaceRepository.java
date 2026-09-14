package az.technest.whereis.space;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpaceRepository extends JpaRepository<Space, UUID> {

    Optional<Space> findByIdAndUserId(UUID id, UUID userId);

    List<Space> findAllByUserIdOrderByNameAsc(UUID userId);

    Optional<Space> findByUserIdAndNormalizedName(UUID userId, String normalizedName);

    boolean existsByUserIdAndNormalizedName(UUID userId, String normalizedName);

    /** Ids only (no entity hydration), ascending so advisory locks are always taken in one order. */
    @Query("select s.id from Space s where s.userId = :userId order by s.id")
    List<UUID> findAllIdsByUserIdOrderByIdAsc(@Param("userId") UUID userId);

    /** Bulk delete; fails on the FK while any location remains. No clearAutomatically — see ItemRepository. */
    @Modifying(flushAutomatically = true)
    @Query("delete from Space s where s.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
