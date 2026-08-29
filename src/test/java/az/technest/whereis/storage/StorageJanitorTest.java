package az.technest.whereis.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorageJanitorTest {

    @Mock
    private StorageDeletionQueueRepository queueRepository;
    @Mock
    private MinioAdapter adapter;
    @InjectMocks
    private StorageJanitor janitor;

    private StorageDeletionQueueEntry entry() {
        return StorageDeletionQueueEntry.builder()
                .id(UUID.randomUUID()).bucket("item-images").objectKey("u/a/i/b/c").attempts(0).build();
    }

    @Test
    void successfulRetryRemovesQueueEntry() {
        StorageDeletionQueueEntry entry = entry();
        when(queueRepository.findTop50ByNextAttemptAtBeforeOrderByNextAttemptAtAsc(any()))
                .thenReturn(List.of(entry));

        janitor.sweep();

        verify(adapter).remove("item-images", "u/a/i/b/c");
        verify(queueRepository).deleteById(entry.getId());
        verify(queueRepository, never()).save(any());
    }

    @Test
    void failedRetryBacksOffAndRecordsError() {
        StorageDeletionQueueEntry entry = entry();
        when(queueRepository.findTop50ByNextAttemptAtBeforeOrderByNextAttemptAtAsc(any()))
                .thenReturn(List.of(entry));
        doThrow(new StorageException("minio down", null)).when(adapter).remove("item-images", "u/a/i/b/c");

        janitor.sweep();

        verify(queueRepository).save(entry);
        verify(queueRepository, never()).deleteById(any());
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getLastError()).contains("minio down");
        assertThat(entry.getNextAttemptAt()).isNotNull();
    }

    @Test
    void backoffIsExponentialAndCapped() {
        assertThat(StorageJanitor.backoff(1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(StorageJanitor.backoff(3)).isEqualTo(Duration.ofMinutes(8));
        assertThat(StorageJanitor.backoff(20)).isEqualTo(Duration.ofMinutes(60));
    }
}
