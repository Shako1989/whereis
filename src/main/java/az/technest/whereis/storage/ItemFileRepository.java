package az.technest.whereis.storage;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemFileRepository extends JpaRepository<ItemFile, UUID> {

    List<ItemFile> findAllByItemIdOrderByCreatedAtAsc(UUID itemId);

    Optional<ItemFile> findByIdAndItemId(UUID id, UUID itemId);

    List<ItemFile> findAllByItemIdInAndIsPrimaryTrue(Collection<UUID> itemIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ItemFile f set f.isPrimary = false where f.itemId = :itemId and f.isPrimary = true")
    int clearPrimary(@Param("itemId") UUID itemId);
}
