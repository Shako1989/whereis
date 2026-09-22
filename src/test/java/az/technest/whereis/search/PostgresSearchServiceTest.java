package az.technest.whereis.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.location.LocationTreeDao;
import az.technest.whereis.search.SearchDao.SearchRow;
import az.technest.whereis.search.dto.ItemSearchResult;
import az.technest.whereis.storage.FileStorageService;
import az.technest.whereis.storage.dto.ItemPrimaryImage;
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
    private FileStorageService fileStorageService;

    private PostgresSearchService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PostgresSearchService(searchDao, treeDao, fileStorageService);
    }

    @Test
    void assemblesPathAndPrimaryImagePerResult() {
        UUID itemId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        when(searchDao.search(eq(userId), eq("passport"), anyInt()))
                .thenReturn(List.of(new SearchRow(itemId, "Passport", locationId, Instant.now())));
        when(treeDao.resolvePaths(List.of(locationId)))
                .thenReturn(Map.of(locationId, List.of("Home", "Bedroom", "Top Drawer")));
        when(fileStorageService.primaryImages(List.of(itemId)))
                .thenReturn(Map.of(itemId, new ItemPrimaryImage(UUID.randomUUID(), "https://minio/presigned")));

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
        when(fileStorageService.primaryImages(List.of(itemId))).thenReturn(Map.of());

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

    // -------------------------------------------------------------------------------------
    // findByNames — the lookup behind an AI-assisted search
    // -------------------------------------------------------------------------------------

    @Test
    void namesComeBackInTheOrderTheModelRankedThemNotTheDatabaseOrder() {
        // SQL IN has no order of its own, and the caller's order IS the model's ranking.
        UUID matkapId = UUID.randomUUID();
        UUID cekicId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Instant now = Instant.now();
        when(searchDao.findByNormalizedNames(eq(userId), eq(List.of("matkap", "cekic")), anyInt()))
                .thenReturn(List.of(
                        new SearchRow(cekicId, "Çəkic", roomId, now),
                        new SearchRow(matkapId, "Matkap", roomId, now)));
        when(treeDao.resolvePaths(List.of(roomId))).thenReturn(Map.of(roomId, List.of("Ev", "Anbar")));
        when(fileStorageService.primaryImages(List.of(cekicId, matkapId))).thenReturn(Map.of());

        List<ItemSearchResult> results = service.findByNames(userId, List.of("matkap", "cekic"), 10);

        assertThat(results).extracting(ItemSearchResult::name).containsExactly("Matkap", "Çəkic");
        assertThat(results.getFirst().locationPath()).containsExactly("Ev", "Anbar");
    }

    @Test
    void aNameThatMatchesNothingIsSimplyAbsentSoAnInventedNameIsHarmless() {
        UUID matkapId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        when(searchDao.findByNormalizedNames(eq(userId), eq(List.of("perforator", "matkap")), anyInt()))
                .thenReturn(List.of(new SearchRow(matkapId, "Matkap", roomId, Instant.now())));
        when(treeDao.resolvePaths(List.of(roomId))).thenReturn(Map.of(roomId, List.of("Ev")));
        when(fileStorageService.primaryImages(List.of(matkapId))).thenReturn(Map.of());

        assertThat(service.findByNames(userId, List.of("perforator", "matkap"), 10))
                .extracting(ItemSearchResult::name).containsExactly("Matkap");
    }

    @Test
    void twoItemsSharingOneNameBothComeBack() {
        // "Matkap" in two rooms is a thing people actually have; a one-row-per-name fold would
        // silently drop the second and the user would be told the wrong place.
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        when(searchDao.findByNormalizedNames(eq(userId), eq(List.of("matkap")), anyInt()))
                .thenReturn(List.of(
                        new SearchRow(first, "Matkap", roomA, Instant.now()),
                        new SearchRow(second, "Matkap", roomB, Instant.now())));
        when(treeDao.resolvePaths(List.of(roomA, roomB)))
                .thenReturn(Map.of(roomA, List.of("Ev"), roomB, List.of("Qaraj")));
        when(fileStorageService.primaryImages(List.of(first, second))).thenReturn(Map.of());

        assertThat(service.findByNames(userId, List.of("matkap"), 10)).hasSize(2);
    }

    @Test
    void anEmptyOrBlankRequestNeverReachesTheDatabase() {
        // An IN () clause is a syntax error, and a blank name would match nothing anyway.
        assertThat(service.findByNames(userId, List.of(), 10)).isEmpty();
        assertThat(service.findByNames(userId, null, 10)).isEmpty();
        assertThat(service.findByNames(userId, List.of("  ", ""), 10)).isEmpty();
        verify(searchDao, never()).findByNormalizedNames(any(), any(), anyInt());
    }

    @Test
    void theBatchRuleHoldsHereToo() {
        // Two queries for the whole set, never one per row — the same contract search() has.
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID room = UUID.randomUUID();
        when(searchDao.findByNormalizedNames(eq(userId), anyList(), anyInt())).thenReturn(List.of(
                new SearchRow(a, "Matkap", room, Instant.now()),
                new SearchRow(b, "Cekic", room, Instant.now())));
        when(treeDao.resolvePaths(List.of(room))).thenReturn(Map.of(room, List.of("Ev")));
        when(fileStorageService.primaryImages(List.of(a, b)))
                .thenReturn(Map.of(a, new ItemPrimaryImage(a, "https://files/x")));

        List<ItemSearchResult> results = service.findByNames(userId, List.of("matkap", "cekic"), 10);

        assertThat(results.getFirst().primaryImageUrl()).isEqualTo("https://files/x");
        assertThat(results.get(1).primaryImageUrl()).isNull();
        verify(treeDao).resolvePaths(anyList());
        verify(fileStorageService).primaryImages(anyList());
    }
}
