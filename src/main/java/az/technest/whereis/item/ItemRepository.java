package az.technest.whereis.item;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, UUID> {

    Optional<Item> findByIdAndUserId(UUID id, UUID userId);

    /** Pessimistic per-item lock used by moveItem so concurrent moves serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Item i where i.id = :id and i.userId = :userId")
    Optional<Item> findForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    Page<Item> findAllByUserId(UUID userId, Pageable pageable);

    Page<Item> findAllByUserIdAndArchivedFalse(UUID userId, Pageable pageable);

    boolean existsByCurrentLocationId(UUID currentLocationId);
}
