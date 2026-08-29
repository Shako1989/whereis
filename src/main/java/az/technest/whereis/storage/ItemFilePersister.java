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
}
