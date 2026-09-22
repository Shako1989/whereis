package az.technest.whereis.search;

import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.util.Names;
import az.technest.whereis.location.LocationTreeDao;
import az.technest.whereis.search.SearchDao.SearchRow;
import az.technest.whereis.search.dto.ItemSearchResult;
import az.technest.whereis.storage.FileStorageService;
import az.technest.whereis.storage.dto.ItemPrimaryImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        return assemble(rows);
    }

    /**
     * Ordered by the caller's list, not by the database: these names arrive already ranked by the
     * model, and {@code IN} would return them in whatever order the rows happen to come back.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ItemSearchResult> findByNames(UUID userId, List<String> normalizedNames, int limit) {
        if (normalizedNames == null || normalizedNames.isEmpty()) {
            return List.of();
        }
        int cappedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<String> wanted = normalizedNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .limit(cappedLimit)
                .toList();
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<SearchRow> rows = searchDao.findByNormalizedNames(userId, wanted, cappedLimit);
        if (rows.isEmpty()) {
            return List.of();
        }
        // One pass over the requested order, so a name the model ranked first stays first. Several
        // items may share a normalized name — "Matkap" twice in two rooms is a thing people have —
        // so this is a multimap fold, not a one-row-per-name lookup.
        Map<String, List<ItemSearchResult>> byName = new LinkedHashMap<>();
        for (ItemSearchResult result : assemble(rows)) {
            byName.computeIfAbsent(Names.normalize(result.name()), key -> new ArrayList<>())
                    .add(result);
        }
        List<ItemSearchResult> ordered = new ArrayList<>();
        for (String name : wanted) {
            ordered.addAll(byName.getOrDefault(name, List.of()));
        }
        return ordered.size() <= cappedLimit ? List.copyOf(ordered)
                : List.copyOf(ordered.subList(0, cappedLimit));
    }

    /**
     * Rows to results: two batch queries + local presigning for the whole set, never per row.
     *
     * <p>{@code primaryImages()} presigns with a local HMAC computation rather than a MinIO call,
     * so it is safe inside a read-only transaction.
     */
    private List<ItemSearchResult> assemble(List<SearchRow> rows) {
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
