package az.technest.whereis.storage;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Post-commit MinIO sweep. Runs inside TransactionSynchronization.afterCommit, where the
 * committed transaction's resources are STILL bound to the thread — a plain repository call
 * would join that completed transaction and its writes would silently never flush. Queue-row
 * deletion therefore runs in an explicit REQUIRES_NEW transaction.
 * A failure here is not an error path — the janitor retries from the queue.
 */
@Slf4j
@Component
public class StorageCleanup {

    private final MinioAdapter adapter;
    private final StorageDeletionQueueRepository queueRepository;
    private final TransactionTemplate requiresNewTx;

    public StorageCleanup(MinioAdapter adapter,
                          StorageDeletionQueueRepository queueRepository,
                          PlatformTransactionManager transactionManager) {
        this.adapter = adapter;
        this.queueRepository = queueRepository;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void tryDeleteAfterCommit(List<StorageDeletionQueueEntry> entries) {
        for (StorageDeletionQueueEntry entry : entries) {
            try {
                adapter.remove(entry.getBucket(), entry.getObjectKey());
                requiresNewTx.executeWithoutResult(status -> queueRepository.deleteById(entry.getId()));
            } catch (RuntimeException e) {
                log.warn("MinIO delete failed for object '{}'; janitor will retry", entry.getObjectKey(), e);
            }
        }
    }
}
