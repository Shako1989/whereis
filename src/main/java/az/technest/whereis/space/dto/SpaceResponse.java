package az.technest.whereis.space.dto;

import az.technest.whereis.space.SpaceType;
import java.time.Instant;
import java.util.UUID;

public record SpaceResponse(
        UUID id,
        String name,
        String description,
        SpaceType type,
        Instant createdAt,
        Instant updatedAt
) {
}
