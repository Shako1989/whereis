package az.technest.whereis.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.location.LocationTreeDao;
import az.technest.whereis.search.SearchDao.SearchRow;
import az.technest.whereis.search.dto.ItemSearchResult;
import az.technest.whereis.storage.ItemFile;
import az.technest.whereis.storage.ItemFileRepository;
import az.technest.whereis.storage.MinioAdapter;
import az.technest.whereis.storage.MinioProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresSearchServiceTest {

    @Mock
    private SearchDao searchDao;
    @Mock
    private LocationTreeDao treeDao;
    @Mock
    private ItemFileRepository itemFileRepository;
    @Mock
    private MinioAdapter minioAdapter;

    private PostgresSearchService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MinioProperties properties = new MinioProperties(
                "http://localhost:9000", null, "key", "secret", "item-images", Duration.ofMinutes(10));
        service = new PostgresSearchService(searchDao, treeDao, itemFileRepository, minioAdapter, properties);
    }

    @Test
    void assemblesPathAndPrimaryImagePerResult() {
        UUID itemId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        when(searchDao.search(eq(userId), eq("passport"), anyInt()))
                .thenReturn(List.of(new SearchRow(itemId, "Passport", locationId, Instant.now())));
        when(treeDao.resolvePaths(List.of(locationId)))
                .thenReturn(Map.of(locationId, List.of("Home", "Bedroom", "Top Drawer")));
        when(itemFileRepository.findAllByItemIdInAndIsPrimaryTrue(List.of(itemId)))
                .thenReturn(List.of(ItemFile.builder().itemId(itemId).objectKey("k").bucket("b")
                        .originalFileName("f.jpg").contentType("image/jpeg").fileSize(1).build()));
        when(minioAdapter.presignGet(eq("k"), any())).thenReturn("https://minio/presigned");

        List<ItemSearchResult> results = service.search(userId, "  Passport ", 20);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().locationPath()).containsExactly("Home", "Bedroom", "Top Drawer");
        assertThat(results.getFirst().primaryImageUrl()).isEqualTo("https://minio/presigned");
    }

    @Test
    void missingPrimaryImageYieldsNullUrl() {
        UUID itemId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        when(searchDao.search(eq(userId), anyString(), anyInt()))
                .thenReturn(List.of(new SearchRow(itemId, "Keys", locationId, Instant.now())));
        when(treeDao.resolvePaths(List.of(locationId))).thenReturn(Map.of(locationId, List.of("Home")));
        when(itemFileRepository.findAllByItemIdInAndIsPrimaryTrue(List.of(itemId))).thenReturn(List.of());

        assertThat(service.search(userId, "keys", 20).getFirst().primaryImageUrl()).isNull();
    }

    @Test
    void rejectsTooShortQueries() {
        assertThatThrownBy(() -> service.search(userId, " p ", 20))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void capsTheLimit() {
        when(searchDao.search(eq(userId), eq("keys"), eq(PostgresSearchService.MAX_LIMIT)))
                .thenReturn(List.of());

        service.search(userId, "keys", 5000);

        verify(searchDao).search(userId, "keys", PostgresSearchService.MAX_LIMIT);
    }
}
