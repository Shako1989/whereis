package az.technest.whereis.space;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpaceRepository extends JpaRepository<Space, UUID> {

    Optional<Space> findByIdAndUserId(UUID id, UUID userId);

    List<Space> findAllByUserIdOrderByNameAsc(UUID userId);

    Optional<Space> findByUserIdAndNormalizedName(UUID userId, String normalizedName);

    boolean existsByUserIdAndNormalizedName(UUID userId, String normalizedName);
}
