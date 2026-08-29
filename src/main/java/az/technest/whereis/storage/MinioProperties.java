package az.technest.whereis.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("minio")
public record MinioProperties(
        String endpoint,
        String externalEndpoint,
        String accessKey,
        String secretKey,
        String bucket,
        Duration presignTtl
) {

    public MinioProperties {
        require(endpoint, "minio.endpoint (MINIO_ENDPOINT)");
        require(accessKey, "minio.access-key (MINIO_ACCESS_KEY)");
        require(secretKey, "minio.secret-key (MINIO_SECRET_KEY)");
        require(bucket, "minio.bucket (MINIO_BUCKET)");
        if (presignTtl == null || presignTtl.isNegative() || presignTtl.isZero()) {
            presignTtl = Duration.ofMinutes(10);
        }
        // SigV4 caps presigned-URL expiry at 7 days; the SDK rejects anything longer.
        if (presignTtl.compareTo(Duration.ofDays(7)) > 0) {
            presignTtl = Duration.ofDays(7);
        }
    }

    /**
     * Presigned URLs must be signed against a host the browser can reach —
     * rewriting the host after signing breaks the signature.
     */
    public String presignEndpoint() {
        return externalEndpoint == null || externalEndpoint.isBlank() ? endpoint : externalEndpoint;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
    }
}
