package az.technest.whereis.location.dto;

import az.technest.whereis.location.LocationType;
import java.time.Instant;
import java.util.UUID;

public record LocationResponse(
        UUID id,
        UUID spaceId,
        UUID parentLocationId,
        String name,
        String description,
        LocationType type,
        Instant createdAt,
        Instant updatedAt
) {
}
