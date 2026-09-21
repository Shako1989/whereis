package az.technest.whereis.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate bean so the short metadata transaction is a real proxy call
 * (FileStorageService.upload itself must stay non-transactional: it talks to MinIO).
 */
@Component
@RequiredArgsConstructor
public class ItemFilePersister {

    private final ItemFileRepository itemFileRepository;

    @Transactional
    public ItemFile saveNew(ItemFile file, boolean primary) {
        if (primary) {
            itemFileRepository.clearPrimary(file.getItemId());
        }
        file.setPrimary(primary);
        return itemFileRepository.save(file);
    }

    /**
     * Records the public copy's key in its OWN short transaction, so the MinIO copy that precedes
     * it is never inside one — the same reason {@link #saveNew} exists.
     */
    @Transactional
    public void recordPublishedKey(java.util.UUID fileId, String publishedObjectKey) {
        itemFileRepository.findById(fileId)
                .ifPresent(file -> file.setPublishedObjectKey(publishedObjectKey));
    }
}
