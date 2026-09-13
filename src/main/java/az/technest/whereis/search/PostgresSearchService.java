package az.technest.whereis.search;

import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.util.Names;
import az.technest.whereis.location.LocationTreeDao;
import az.technest.whereis.search.SearchDao.SearchRow;
import az.technest.whereis.search.dto.ItemSearchResult;
import az.technest.whereis.storage.FileStorageService;
import az.technest.whereis.storage.dto.ItemPrimaryImage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostgresSearchService implements SearchService {

    static final int MAX_LIMIT = 50;

    private final SearchDao searchDao;
    private final LocationTreeDao treeDao;
    private final FileStorageService fileStorageService;

    @Override
    @Transactional(readOnly = true)
    public List<ItemSearchResult> search(UUID userId, String query, int limit) {
        String normalized = Names.normalize(query);
        if (normalized == null || normalized.length() < 2) {
            throw new BadRequestException(ErrorCode.VALIDATION_ERROR,
                    "Search query must be at least 2 characters");
        }
        if (normalized.length() > 100) {
            normalized = normalized.substring(0, 100);
        }
        int cappedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<SearchRow> rows = searchDao.search(userId, normalized, cappedLimit);
        if (rows.isEmpty()) {
            return List.of();
        }
        // Two batch queries + local presigning for the whole result set — no per-row lookups.
        // primaryImages() presigns with a local HMAC computation, not a MinIO call, so it is
        // safe inside this read-only transaction.
        Map<UUID, List<String>> paths = treeDao.resolvePaths(
                rows.stream().map(SearchRow::currentLocationId).distinct().toList());
        Map<UUID, ItemPrimaryImage> covers = fileStorageService.primaryImages(
                rows.stream().map(SearchRow::id).toList());
        return rows.stream()
                .map(row -> new ItemSearchResult(
                        row.id(),
                        row.name(),
                        paths.getOrDefault(row.currentLocationId(), List.of()),
                        urlOrNull(covers.get(row.id())),
                        row.updatedAt()))
                .toList();
    }

    private static String urlOrNull(ItemPrimaryImage image) {
        return image == null ? null : image.url();
    }
}
