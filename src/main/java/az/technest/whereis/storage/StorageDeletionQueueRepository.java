package az.technest.whereis.storage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorageDeletionQueueRepository extends JpaRepository<StorageDeletionQueueEntry, UUID> {

    List<StorageDeletionQueueEntry> findTop50ByNextAttemptAtBeforeOrderByNextAttemptAtAsc(Instant now);
}
