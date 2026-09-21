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
        String publicBucket,
        Duration presignTtl
) {

    public MinioProperties {
        require(endpoint, "minio.endpoint (MINIO_ENDPOINT)");
        require(accessKey, "minio.access-key (MINIO_ACCESS_KEY)");
        require(secretKey, "minio.secret-key (MINIO_SECRET_KEY)");
        require(bucket, "minio.bucket (MINIO_BUCKET)");
        // DELIBERATELY NOT required, and the blank case is a supported deployable state rather than
        // a misconfiguration — see publishedPhotosEnabled().
        publicBucket = publicBucket == null || publicBucket.isBlank() ? null : publicBucket.trim();
        if (publicBucket != null && publicBucket.equals(bucket)) {
            // Anonymous read is granted on the whole bucket, so one bucket for both would publish
            // every private photo the moment an operator ran `mc anonymous set download`.
            throw new IllegalStateException("minio.public-bucket (MINIO_PUBLIC_BUCKET) must not be "
                    + "the same bucket as minio.bucket — the public one is world-readable");
        }
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
     * rewriting the host after signing breaks the signature. The public bucket's URLs are built
     * against the same host for the same reason, minus the signing.
     */
    public String presignEndpoint() {
        return externalEndpoint == null || externalEndpoint.isBlank() ? endpoint : externalEndpoint;
    }

    /**
     * Whether a published marketplace photo can be stored at all.
     *
     * <p><strong>Blank is a deployable state, on purpose, and it degrades rather than failing.</strong>
     * A published copy lives in a SECOND, genuinely world-readable bucket that this application
     * never creates (creating it would create it without the anonymous-read policy, and every
     * listing image would then 403 with nothing saying why). Until an operator has created that
     * bucket and named it here, listings publish and the board serves them — without an image.
     * That is the same shape {@code whereis.play.provider=disabled} already uses for billing: the
     * feature is off, everything around it works, and switching it on is one environment variable.
     */
    public boolean publishedPhotosEnabled() {
        return publicBucket != null;
    }

    /**
     * The permanent, signature-free address of a published object.
     *
     * <p>Takes the bucket from the ROW rather than from this configuration, exactly as
     * {@code MinioAdapter.remove(bucket, key)} does: a row written before a bucket rename still
     * resolves to the bucket its object is actually in.
     */
    public String publicUrlOf(String bucket, String objectKey) {
        String base = presignEndpoint();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + bucket + "/" + objectKey;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
    }
}
