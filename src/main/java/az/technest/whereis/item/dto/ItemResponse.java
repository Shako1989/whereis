package az.technest.whereis.item.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ItemResponse(
        UUID id,
        String name,
        String description,
        String category,
        UUID currentLocationId,
        List<String> locationPath,
        boolean archived,
        Instant createdAt,
        Instant updatedAt
) {
}
