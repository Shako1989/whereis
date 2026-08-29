package az.technest.whereis.item;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemLocationHistoryRepository extends JpaRepository<ItemLocationHistory, UUID> {

    List<ItemLocationHistory> findAllByItemIdOrderByPlacedAtDesc(UUID itemId);

    /**
     * Targeted close of the single open record; the partial unique index is the concurrency backstop.
     * Deliberately NO clearAutomatically: the caller keeps mutating the managed (locked) Item
     * afterwards, and clearing would detach it and silently drop that update. No history
     * entities are ever loaded in the same persistence context, so nothing can go stale.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ItemLocationHistory h set h.removedAt = :now
            where h.itemId = :itemId and h.removedAt is null
            """)
    int closeOpen(@Param("itemId") UUID itemId, @Param("now") Instant now);
}
