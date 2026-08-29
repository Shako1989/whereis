package az.technest.whereis.search.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ItemSearchResult(
        UUID id,
        String name,
        List<String> locationPath,
        String primaryImageUrl,
        Instant updatedAt
) {
}
