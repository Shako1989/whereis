package az.technest.whereis.item.dto;

import java.time.Instant;
import java.util.UUID;

public record ItemHistoryResponse(
        UUID id,
        UUID locationId,
        String locationPath,
        String note,
        Instant placedAt,
        Instant removedAt
) {
}
