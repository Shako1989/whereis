package az.technest.whereis.search;

import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.util.Names;
import az.technest.whereis.location.LocationTreeDao;
import az.technest.whereis.search.SearchDao.SearchRow;
import az.technest.whereis.search.dto.ItemSearchResult;
import az.technest.whereis.storage.ItemFile;
import az.technest.whereis.storage.ItemFileRepository;
import az.technest.whereis.storage.MinioAdapter;
import az.technest.whereis.storage.MinioProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostgresSearchService implements SearchService {

    static final int MAX_LIMIT = 50;

    private final SearchDao searchDao;
    private final LocationTreeDao treeDao;
    private final ItemFileRepository itemFileRepository;
    private final MinioAdapter minioAdapter;
    private final MinioProperties minioProperties;

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
        Map<UUID, List<String>> paths = treeDao.resolvePaths(
                rows.stream().map(SearchRow::currentLocationId).distinct().toList());
        Map<UUID, ItemFile> primaryByItem = itemFileRepository
                .findAllByItemIdInAndIsPrimaryTrue(rows.stream().map(SearchRow::id).toList())
                .stream()
                .collect(Collectors.toMap(ItemFile::getItemId, Function.identity()));
        return rows.stream()
                .map(row -> new ItemSearchResult(
                        row.id(),
                        row.name(),
                        paths.getOrDefault(row.currentLocationId(), List.of()),
                        presignOrNull(primaryByItem.get(row.id())),
                        row.updatedAt()))
                .toList();
    }

    private String presignOrNull(ItemFile file) {
        // Presigning is a local HMAC computation, not a network call — cheap per row.
        return file == null ? null : minioAdapter.presignGet(file.getObjectKey(), minioProperties.presignTtl());
    }
}
