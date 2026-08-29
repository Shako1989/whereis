package az.technest.whereis.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries queued MinIO deletions with exponential backoff. */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageJanitor {

    private static final int MAX_BACKOFF_MINUTES = 60;

    private final StorageDeletionQueueRepository queueRepository;
    private final MinioAdapter adapter;

    @Scheduled(fixedDelayString = "${storage.janitor.delay}")
    public void sweep() {
        Instant now = Instant.now();
        List<StorageDeletionQueueEntry> due = queueRepository.findTop50ByNextAttemptAtBeforeOrderByNextAttemptAtAsc(now);
        for (StorageDeletionQueueEntry entry : due) {
            try {
                adapter.remove(entry.getBucket(), entry.getObjectKey());
                queueRepository.deleteById(entry.getId());
            } catch (RuntimeException e) {
                entry.setAttempts(entry.getAttempts() + 1);
                entry.setNextAttemptAt(now.plus(backoff(entry.getAttempts())));
                entry.setLastError(truncate(e.getMessage()));
                queueRepository.save(entry);
                log.warn("Janitor retry {} failed for object '{}'", entry.getAttempts(), entry.getObjectKey());
            }
        }
    }

    static Duration backoff(int attempts) {
        long minutes = Math.min(1L << Math.min(attempts, 10), MAX_BACKOFF_MINUTES);
        return Duration.ofMinutes(minutes);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
