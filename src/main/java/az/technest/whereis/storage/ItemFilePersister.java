package az.technest.whereis.storage;

import java.util.UUID;
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
     * Records the public copy's bucket AND key in its OWN short transaction, so the MinIO read and
     * write that precede it are never inside one — the same reason {@link #saveNew} exists.
     *
     * <p>Both columns or neither: {@code ck_item_files_published_pair} refuses a half-written pair,
     * because the deletion outbox reads them together.
     */
    @Transactional
    public void recordPublishedKey(UUID fileId, String publishedBucket, String publishedObjectKey) {
        itemFileRepository.findById(fileId).ifPresent(file -> {
            file.setPublishedBucket(publishedBucket);
            file.setPublishedObjectKey(publishedObjectKey);
        });
    }
}
