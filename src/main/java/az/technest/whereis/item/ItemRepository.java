package az.technest.whereis.item;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Row-locks every item of the user in ONE statement — the batch analogue of {@link #findForUpdate}.
     * Native so that {@code FOR UPDATE} is guaranteed on the wire and no entities are hydrated for a
     * large account; ids only. A concurrent photo upload inserting {@code item_files} needs
     * {@code FOR KEY SHARE} on the parent row, which this blocks until the deleting transaction ends.
     */
    @Query(value = "select id from items where user_id = :userId for update", nativeQuery = true)
    List<UUID> lockAllForUser(@Param("userId") UUID userId);

    /**
     * Bulk delete of a user's items; the database cascades {@code item_files} and
     * {@code item_location_history}. Deliberately NO clearAutomatically: the account-deletion
     * transaction still holds the managed {@code User} it loaded for the password check, and
     * clearing would detach it. No Item entity is ever loaded in that transaction, so nothing can go stale.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from Item i where i.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
