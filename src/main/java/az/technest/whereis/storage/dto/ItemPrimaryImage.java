package az.technest.whereis.storage.dto;

import java.util.UUID;

/**
 * An item's cover photo as other modules need it: the stable file id plus a short-lived
 * presigned URL. The id is part of the contract on purpose — the URL rotates every
 * {@code minio.presign-ttl} (~10 minutes), so clients key their image caches on the id.
 */
public record ItemPrimaryImage(UUID fileId, String url) {
}
