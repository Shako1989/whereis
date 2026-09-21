package az.technest.whereis.storage;

import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.error.NotFoundException;
import az.technest.whereis.common.util.Names;
import az.technest.whereis.item.ItemNotFoundException;
import az.technest.whereis.item.ItemRepository;
import az.technest.whereis.marketplace.ListingConflictException;
import az.technest.whereis.storage.dto.ItemFileResponse;
import az.technest.whereis.storage.dto.ItemPrimaryImage;
import az.technest.whereis.storage.dto.PresignedUrlResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
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
        // A published cover is referenced by listings.cover_file_id, whose composite FK is NO
        // ACTION — so deleting it alone would fail at end of statement with a 23503 and a 500.
        // This is the same "undo something first" answer the rest of this API already gives
        // (LOCATION_NOT_EMPTY, SPACE_NOT_EMPTY), and it lives here rather than in marketplace/ so
        // that storage does not depend on it.
        if (file.getPublishedObjectKey() != null) {
            throw ListingConflictException.itemListed("delete this photo");
        }
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
        List<StorageDeletionQueueEntry> entries = new ArrayList<>(
                queueRepository.saveAll(files.stream().map(this::queueEntry).toList()));
        // ...and the PUBLIC copies. Without this the opaque p/ objects would outlive the account
        // that published them, still reachable by anyone holding a presigned URL — the orphan the
        // outbox exists to prevent, on the one class of object that faces the open internet.
        List<ItemFile> published = files.stream()
                .filter(file -> file.getPublishedObjectKey() != null)
                .toList();
        if (!published.isEmpty()) {
            entries.addAll(queueRepository.saveAll(
                    published.stream().map(this::publishedQueueEntry).toList()));
        }
        sweepAfterCommit(entries);
    }

    /**
     * Called inside the account-deletion transaction, BEFORE the items are deleted: one
     * {@code INSERT … SELECT} puts every object of every item of the user into the outbox.
     *
     * <p>Deliberately registers NO {@code afterCommit} sweep, unlike {@link #delete} and
     * {@link #enqueueAllForItem}. The sweep removes objects one by one on the request thread; for
     * an account with thousands of photos that would put thousands of MinIO round trips between
     * the commit and the 204. The bulk insert also hands back no entities to sweep. The outbox
     * already guarantees eventual removal and {@code StorageJanitor} is its single consumer — so
     * there is no MinIO call anywhere in the account-deletion request path, and MinIO being down
     * cannot block the delete.
     *
     * @return the number of outbox rows written (one per {@code item_files} row)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueueAllForUser(UUID userId) {
        return queueRepository.enqueueAllFilesOfUser(userId)
                + queueRepository.enqueuePublishedCopiesOfUser(userId);
    }

    /**
     * Copies this photo to an OPAQUE public key so the marketplace board can presign it without
     * publishing {@code u/{userId}/i/{itemId}/{fileId}} to anonymous visitors, and records the key.
     *
     * <p>Deliberately NOT {@code @Transactional}, and the order is {@link #upload}'s verbatim: the
     * object is copied FIRST, then a short metadata transaction records it, and a failure of the
     * second compensates by enqueueing the copy — so a crash between the two leaves a queued
     * object rather than a row pointing at nothing.
     *
     * <p>Idempotent: a photo that already has a copy keeps it. Re-publishing the same cover must
     * not mint a second object nobody will ever collect.
     */
    public String publishPhoto(UUID userId, UUID itemId, UUID fileId) {
        requireOwnedItem(userId, itemId);
        ItemFile file = requireFile(itemId, fileId);
        if (file.getPublishedObjectKey() != null) {
            return file.getPublishedObjectKey();
        }
        // No user id, no item id, no file id, no listing id, no structure at all.
        String publishedKey = "p/" + UUID.randomUUID();
        adapter.copy(file.getObjectKey(), publishedKey);
        try {
            persister.recordPublishedKey(fileId, publishedKey);
        } catch (RuntimeException e) {
            compensateUpload(publishedKey);
            throw e;
        }
        return publishedKey;
    }

    /**
     * Drops the public copy in ONE transaction — clear the column, enqueue the object — which is
     * {@link #delete}'s shape. The board 404s the listing the moment its row ends; this is what
     * makes the already-minted presigned URLs expire early rather than at their TTL.
     */
    @Transactional
    public void unpublishPhoto(UUID itemId, UUID fileId) {
        ItemFile file = itemFileRepository.findByIdAndItemId(fileId, itemId).orElse(null);
        if (file == null || file.getPublishedObjectKey() == null) {
            return;
        }
        StorageDeletionQueueEntry entry = queueRepository.save(publishedQueueEntry(file));
        file.setPublishedObjectKey(null);
        sweepAfterCommit(List.of(entry));
    }

    /**
     * Presigns the PUBLISHED copies of the given {@code item_files} ids, for the anonymous board.
     *
     * <p><strong>Deliberately not {@link #primaryImages}.</strong> That method's contract is "the
     * ids must already have been produced by a userId-scoped finder", and this caller has no user
     * at all. Here ownership is established by a FOREIGN KEY instead: these ids come from
     * {@code listings.cover_file_id}, which only a publish request that had already proved
     * ownership can write, and whose composite FK guarantees the file belongs to the listed item.
     * Reusing {@code primaryImages} would leave it without a single honest precondition.
     *
     * <p>ONE query for the whole page. Presigning is a local HMAC, so read-only is fine.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> presignPublished(Collection<UUID> fileIds) {
        if (fileIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> urls = new HashMap<>();
        for (ItemFile file : itemFileRepository.findAllById(fileIds)) {
            if (file.getPublishedObjectKey() != null) {
                urls.put(file.getId(), adapter.presignGet(
                        file.getPublishedObjectKey(), properties.presignTtl()));
            }
        }
        return urls;
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

    private StorageDeletionQueueEntry publishedQueueEntry(ItemFile file) {
        return StorageDeletionQueueEntry.builder()
                .bucket(file.getBucket())
                .objectKey(file.getPublishedObjectKey())
                .build();
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
