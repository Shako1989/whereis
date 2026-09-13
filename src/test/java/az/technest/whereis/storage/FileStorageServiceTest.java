package az.technest.whereis.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.item.Item;
import az.technest.whereis.item.ItemRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class FileStorageServiceTest {

    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1, 2, 3, 4, 5, 6, 7};

    @Mock
    private ItemRepository itemRepository;
    @Mock
    private ItemFileRepository itemFileRepository;
    @Mock
    private StorageDeletionQueueRepository queueRepository;
    @Mock
    private MinioAdapter adapter;
    @Mock
    private ItemFilePersister persister;
    @Mock
    private StorageCleanup cleanup;

    private FileStorageService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MinioProperties properties = new MinioProperties(
                "http://localhost:9000", null, "key", "secret", "item-images", Duration.ofMinutes(10));
        service = new FileStorageService(itemRepository, itemFileRepository, queueRepository,
                adapter, properties, persister, cleanup);
        // Lenient: the batch cover lookup is ownership-agnostic by contract and never calls this.
        org.mockito.Mockito.lenient().when(itemRepository.findByIdAndUserId(itemId, userId)).thenReturn(Optional.of(
                Item.builder().id(itemId).userId(userId).currentLocationId(UUID.randomUUID())
                        .name("Keys").normalizedName("keys").archived(false).build()));
    }

    @AfterEach
    void cleanupSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void uploadPutsObjectBeforeMetadataAndReturnsResponse() {
        when(persister.saveNew(any(ItemFile.class), eq(false))).thenAnswer(inv -> {
            ItemFile file = inv.getArgument(0);
            file.setId(UUID.randomUUID());
            return file;
        });
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG_BYTES);

        var response = service.upload(userId, itemId, file, false);

        InOrder order = inOrder(adapter, persister);
        order.verify(adapter).put(anyString(), any(), anyLong(), eq("image/jpeg"));
        order.verify(persister).saveNew(any(ItemFile.class), eq(false));
        assertThat(response.originalFileName()).isEqualTo("photo.jpg");
    }

    @Test
    void uploadCompensatesWithObjectDeleteWhenMetadataInsertFails() {
        when(persister.saveNew(any(ItemFile.class), eq(false)))
                .thenThrow(new DataIntegrityViolationException("boom"));
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG_BYTES);

        assertThatThrownBy(() -> service.upload(userId, itemId, file, false))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(adapter).remove(anyString());
        verify(queueRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void failedCompensationFallsBackToTheDeletionOutbox() {
        when(persister.saveNew(any(ItemFile.class), eq(false)))
                .thenThrow(new DataIntegrityViolationException("boom"));
        org.mockito.Mockito.doThrow(new StorageException("minio down", null))
                .when(adapter).remove(anyString());
        when(adapter.bucket()).thenReturn("item-images");
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG_BYTES);

        assertThatThrownBy(() -> service.upload(userId, itemId, file, false))
                .isInstanceOf(DataIntegrityViolationException.class);

        // MinIO down during compensation: the orphan lands in the outbox for the janitor.
        verify(queueRepository).save(any(StorageDeletionQueueEntry.class));
    }

    @Test
    void uploadRejectsContentTypeSpoofing() {
        // Claims JPEG but carries PNG magic bytes.
        byte[] pngBytes = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        MockMultipartFile file = new MockMultipartFile("file", "evil.jpg", "image/jpeg", pngBytes);

        assertThatThrownBy(() -> service.upload(userId, itemId, file, false))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void objectKeyIsServerGeneratedNeverFromFilename() {
        when(persister.saveNew(any(ItemFile.class), eq(false))).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile file = new MockMultipartFile(
                "file", "../../etc/passwd.jpg", "image/jpeg", JPEG_BYTES);

        service.upload(userId, itemId, file, false);

        verify(adapter).put(org.mockito.ArgumentMatchers.matches(
                "u/" + userId + "/i/" + itemId + "/[0-9a-f-]{36}"), any(), anyLong(), anyString());
    }

    private static ItemFile photo(UUID id, UUID itemId, boolean primary, Instant createdAt) {
        return ItemFile.builder().id(id).itemId(itemId).bucket("item-images")
                .objectKey("u/x/i/" + itemId + "/" + id).originalFileName("a.jpg")
                .contentType("image/jpeg").fileSize(3).isPrimary(primary).createdAt(createdAt).build();
    }

    /** Stubs the cover finder with rows in the order the ORDER BY would deliver them. */
    private void coverRows(List<UUID> itemIds, ItemFile... rowsInFinderOrder) {
        when(itemFileRepository.findAllByItemIdInOrderByIsPrimaryDescCreatedAtAscIdAsc(itemIds))
                .thenReturn(List.of(rowsInFinderOrder));
        when(adapter.presignGet(anyString(), any()))
                .thenAnswer(inv -> "https://minio/" + inv.getArgument(0));
    }

    @Test
    void primaryImagesReturnsFileIdAndPresignedUrlPerItemInOneQuery() {
        UUID otherItemId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        coverRows(List.of(itemId, otherItemId), photo(fileId, itemId, true, Instant.now()));

        var covers = service.primaryImages(List.of(itemId, otherItemId));

        assertThat(covers).containsOnlyKeys(itemId);
        assertThat(covers.get(itemId).fileId()).isEqualTo(fileId);
        assertThat(covers.get(itemId).url()).isEqualTo("https://minio/u/x/i/" + itemId + "/" + fileId);
        // One query for the batch — never one per item.
        verify(itemFileRepository).findAllByItemIdInOrderByIsPrimaryDescCreatedAtAscIdAsc(List.of(itemId, otherItemId));
    }

    @Test
    void primaryImagesSkipsTheQueryForAnEmptyBatch() {
        assertThat(service.primaryImages(List.of())).isEmpty();

        verify(itemFileRepository, org.mockito.Mockito.never())
                .findAllByItemIdInOrderByIsPrimaryDescCreatedAtAscIdAsc(any());
    }

    @Test
    void primaryImagesPrefersThePrimaryOverAnOlderNonPrimaryPhoto() {
        UUID otherItemId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        UUID newerPrimary = UUID.randomUUID();
        UUID olderPlain = UUID.randomUUID();
        UUID otherOldest = UUID.randomUUID();
        UUID otherNewer = UUID.randomUUID();
        // Finder order is global, not grouped by item: all primaries first, then everything
        // else oldest-first — so rows of different items interleave.
        coverRows(List.of(itemId, otherItemId),
                photo(newerPrimary, itemId, true, t0.plusSeconds(60)),
                photo(otherOldest, otherItemId, false, t0),
                photo(olderPlain, itemId, false, t0.plusSeconds(1)),
                photo(otherNewer, otherItemId, false, t0.plusSeconds(30)));

        var covers = service.primaryImages(List.of(itemId, otherItemId));

        assertThat(covers).containsOnlyKeys(itemId, otherItemId);
        assertThat(covers.get(itemId).fileId()).isEqualTo(newerPrimary);
        assertThat(covers.get(otherItemId).fileId()).isEqualTo(otherOldest);
        // Only the two winners are presigned, not every row of the page.
        verify(adapter, org.mockito.Mockito.times(2)).presignGet(anyString(), any());
    }

    @Test
    void primaryImagesFallsBackToTheOldestPhotoWhenNoneIsPrimary() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        UUID oldest = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        coverRows(List.of(itemId),
                photo(oldest, itemId, false, t0),
                photo(middle, itemId, false, t0.plusSeconds(5)),
                photo(newest, itemId, false, t0.plusSeconds(10)));

        var covers = service.primaryImages(List.of(itemId));

        assertThat(covers.get(itemId).fileId()).isEqualTo(oldest);
    }

    @Test
    void primaryImagesIsFirstWinsWhenTimestampsTieSoTheIdTieBreakDecides() {
        Instant sameInstant = Instant.parse("2026-01-01T00:00:00Z");
        UUID lowerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higherId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        // Equal created_at: the finder's trailing "id ASC" puts the lower id first and the
        // service must keep that row rather than re-deciding on its own.
        coverRows(List.of(itemId),
                photo(lowerId, itemId, false, sameInstant),
                photo(higherId, itemId, false, sameInstant));

        var covers = service.primaryImages(List.of(itemId));

        assertThat(covers.get(itemId).fileId()).isEqualTo(lowerId);
    }

    @Test
    void deleteWritesOutboxRowAndSweepsAfterCommit() {
        UUID fileId = UUID.randomUUID();
        ItemFile file = ItemFile.builder().id(fileId).itemId(itemId).bucket("item-images")
                .objectKey("u/x/i/y/z").originalFileName("a.jpg").contentType("image/jpeg").fileSize(3).build();
        when(itemFileRepository.findByIdAndItemId(fileId, itemId)).thenReturn(Optional.of(file));
        when(queueRepository.save(any(StorageDeletionQueueEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
        service.delete(userId, itemId, fileId);

        verify(queueRepository).save(any(StorageDeletionQueueEntry.class));
        verify(itemFileRepository).delete(file);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        // Nothing hits MinIO before the commit.
        verify(cleanup, org.mockito.Mockito.never()).tryDeleteAfterCommit(any());

        synchronizations.getFirst().afterCommit();
        verify(cleanup).tryDeleteAfterCommit(any());
    }
}
