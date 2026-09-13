package az.technest.whereis.storage;

import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.error.NotFoundException;
import az.technest.whereis.common.util.Names;
import az.technest.whereis.item.ItemNotFoundException;
import az.technest.whereis.item.ItemRepository;
import az.technest.whereis.storage.dto.ItemFileResponse;
import az.technest.whereis.storage.dto.ItemPrimaryImage;
import az.technest.whereis.storage.dto.PresignedUrlResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final ItemRepository itemRepository;
    private final ItemFileRepository itemFileRepository;
    private final StorageDeletionQueueRepository queueRepository;
    private final MinioAdapter adapter;
    private final MinioProperties properties;
    private final ItemFilePersister persister;
    private final StorageCleanup cleanup;

    /**
     * Upload strategy for the PG/MinIO split: put the object FIRST, then write metadata
     * in a short transaction. On metadata failure the object is compensating-deleted.
     * Worst case is an invisible orphaned object — never a DB row pointing at nothing.
     * Deliberately NOT @Transactional: no DB transaction may span a MinIO call.
     */
    public ItemFileResponse upload(UUID userId, UUID itemId, MultipartFile file, boolean primary) {
        requireOwnedItem(userId, itemId);
        ImageSignatures.validate(file);
        UUID fileId = UUID.randomUUID();
        String objectKey = "u/%s/i/%s/%s".formatted(userId, itemId, fileId);
        try (InputStream in = file.getInputStream()) {
            adapter.put(objectKey, in, file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new StorageException("Failed to read uploaded file", e);
        }
        try {
            ItemFile saved = persister.saveNew(ItemFile.builder()
                    .itemId(itemId)
                    .bucket(adapter.bucket())
                    .objectKey(objectKey)
                    // Original filename is display metadata only — never used in object keys.
                    .originalFileName(sanitizeFilename(file.getOriginalFilename()))
                    .contentType(file.getContentType())
                    .fileSize(file.getSize())
                    .build(), primary);
            return toResponse(saved);
        } catch (RuntimeException e) {
            compensateUpload(objectKey);
            throw e;
        }
    }

    /**
     * Compensation for "object stored but metadata insert failed": try to remove the object;
     * if MinIO is also failing, fall back to the deletion outbox so the janitor removes it later.
     */
    private void compensateUpload(String objectKey) {
        try {
            adapter.remove(objectKey);
        } catch (RuntimeException cleanupFailure) {
            queueRepository.save(StorageDeletionQueueEntry.builder()
                    .bucket(adapter.bucket())
                    .objectKey(objectKey)
                    .build());
        }
    }

    @Transactional(readOnly = true)
    public List<ItemFileResponse> list(UUID userId, UUID itemId) {
        requireOwnedItem(userId, itemId);
        return itemFileRepository.findAllByItemIdOrderByCreatedAtAsc(itemId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Delete strategy: metadata delete + outbox row in ONE transaction, then a best-effort
     * MinIO delete after commit. If that fails, the janitor retries from the outbox.
     */
    @Transactional
    public void delete(UUID userId, UUID itemId, UUID fileId) {
        requireOwnedItem(userId, itemId);
        ItemFile file = requireFile(itemId, fileId);
        StorageDeletionQueueEntry entry = queueRepository.save(queueEntry(file));
        itemFileRepository.delete(file);
        sweepAfterCommit(List.of(entry));
    }

    /**
     * Called inside the item-deletion transaction: enqueues every object of the item
     * before the cascade removes the metadata rows.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueAllForItem(UUID itemId) {
        List<ItemFile> files = itemFileRepository.findAllByItemIdOrderByCreatedAtAsc(itemId);
        if (files.isEmpty()) {
            return;
        }
        List<StorageDeletionQueueEntry> entries = queueRepository.saveAll(
                files.stream().map(this::queueEntry).toList());
        sweepAfterCommit(entries);
    }

    /**
     * Cover photo (stable file id + presigned URL) for every given item that has at least one
     * photo: the photo flagged primary if there is one, otherwise the OLDEST upload. Items with
     * no photos at all are simply absent from the map. Most legacy photos were uploaded with
     * {@code primary=false}, so "primary only" would leave them coverless; this matches what the
     * retired Android workaround did. ONE query for the whole batch — this is the only supported
     * way to resolve covers for a page of items, never per row.
     *
     * <p>Ownership is the caller's responsibility: the ids must already have been produced by a
     * userId-scoped finder, exactly like {@code LocationTreeDao}'s batch path resolution.
     *
     * <p>Safe to call inside a read-only transaction: presigning is a local HMAC computation over
     * the object key, NOT a request to MinIO, so the "no MinIO call inside a DB transaction" rule
     * is not in play here.
     */
    @Transactional(readOnly = true)
    public Map<UUID, ItemPrimaryImage> primaryImages(Collection<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        // This fetches EVERY file of the page's items, not just the primaries: the fallback to
        // the oldest photo cannot be expressed as a row filter, and it must not become a second
        // query. Acceptable because an item carries a handful of photos, it is still one round
        // trip per page, and the client code this replaces made one call PER ROW.
        //
        // The finder orders rows winner-first per item (primary, then oldest, then lowest id),
        // so a first-wins fold over that list picks the cover. The decision is taken from the
        // ORDER BY, never from map iteration order; only winners are presigned.
        Map<UUID, ItemPrimaryImage> covers = new HashMap<>();
        for (ItemFile file : itemFileRepository.findAllByItemIdInOrderByIsPrimaryDescCreatedAtAscIdAsc(itemIds)) {
            covers.computeIfAbsent(file.getItemId(), itemId -> toPrimaryImage(file));
        }
        return Map.copyOf(covers);
    }

    @Transactional(readOnly = true)
    public PresignedUrlResponse presign(UUID userId, UUID itemId, UUID fileId) {
        requireOwnedItem(userId, itemId);
        ItemFile file = requireFile(itemId, fileId);
        String url = adapter.presignGet(file.getObjectKey(), properties.presignTtl());
        return new PresignedUrlResponse(url, Instant.now().plus(properties.presignTtl()));
    }

    private ItemPrimaryImage toPrimaryImage(ItemFile file) {
        return new ItemPrimaryImage(file.getId(),
                adapter.presignGet(file.getObjectKey(), properties.presignTtl()));
    }

    private void sweepAfterCommit(List<StorageDeletionQueueEntry> entries) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.tryDeleteAfterCommit(entries);
            }
        });
    }

    private StorageDeletionQueueEntry queueEntry(ItemFile file) {
        return StorageDeletionQueueEntry.builder()
                .bucket(file.getBucket())
                .objectKey(file.getObjectKey())
                .build();
    }

    private void requireOwnedItem(UUID userId, UUID itemId) {
        itemRepository.findByIdAndUserId(itemId, userId).orElseThrow(ItemNotFoundException::new);
    }

    private ItemFile requireFile(UUID itemId, UUID fileId) {
        return itemFileRepository.findByIdAndItemId(fileId, itemId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.FILE_NOT_FOUND, "File not found"));
    }

    private ItemFileResponse toResponse(ItemFile file) {
        return new ItemFileResponse(file.getId(), file.getItemId(), file.getOriginalFileName(),
                file.getContentType(), file.getFileSize(), file.isPrimary(), file.getCreatedAt());
    }

    private static String sanitizeFilename(String raw) {
        String cleaned = Names.clean(raw);
        if (cleaned == null || cleaned.isBlank()) {
            return "unnamed";
        }
        // Strip any path components and control characters; keep it display-safe.
        String basename = cleaned.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1);
        basename = basename.replaceAll("[\\p{Cntrl}]", "");
        if (basename.isBlank()) {
            return "unnamed";
        }
        return basename.length() <= 255 ? basename : basename.substring(basename.length() - 255);
    }
}
