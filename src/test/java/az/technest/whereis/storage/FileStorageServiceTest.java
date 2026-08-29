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
        when(itemRepository.findByIdAndUserId(itemId, userId)).thenReturn(Optional.of(
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
