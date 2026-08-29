package az.technest.whereis.storage.dto;

import java.time.Instant;
import java.util.UUID;

public record ItemFileResponse(
        UUID id,
        UUID itemId,
        String originalFileName,
        String contentType,
        long fileSize,
        boolean isPrimary,
        Instant createdAt
) {
}
