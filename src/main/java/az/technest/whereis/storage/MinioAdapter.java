package az.technest.whereis.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** The only class that talks to the MinIO SDK. */
@Slf4j
@Component
public class MinioAdapter {

    private final MinioClient opsClient;
    private final MinioClient presignClient;
    private final MinioProperties properties;

    public MinioAdapter(@Qualifier("minioOpsClient") MinioClient opsClient,
                        @Qualifier("minioPresignClient") MinioClient presignClient,
                        MinioProperties properties) {
        this.opsClient = opsClient;
        this.presignClient = presignClient;
        this.properties = properties;
    }

    /** The PRIVATE bucket: everything a user uploads. */
    public String bucket() {
        return properties.bucket();
    }

    public void ensureBucket() {
        try {
            boolean exists = opsClient.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build());
            if (!exists) {
                opsClient.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
                log.info("Created MinIO bucket '{}'", properties.bucket());
            }
        } catch (ErrorResponseException e) {
            // Concurrent creation by another replica is fine.
            if (!"BucketAlreadyOwnedByYou".equals(e.errorResponse().code())) {
                throw new StorageException("Failed to ensure MinIO bucket", e);
            }
        } catch (Exception e) {
            throw new StorageException("Failed to ensure MinIO bucket", e);
        }
    }

    /**
     * Whether a bucket exists. Used to REPORT on the public bucket at startup, never to create it:
     * this application must not own the world-readable bucket's lifecycle, because it would create
     * it without the anonymous-read policy and every published image would 403.
     */
    public boolean bucketExists(String bucket) {
        try {
            return opsClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        } catch (Exception e) {
            throw new StorageException("Failed to check MinIO bucket '" + bucket + "'", e);
        }
    }

    public void put(String objectKey, InputStream stream, long size, String contentType) {
        put(properties.bucket(), objectKey, stream, size, contentType, Map.of());
    }

    /**
     * @param headers stored HTTP response headers MinIO replays on every GET — this is how a
     *                published object carries its own {@code Cache-Control} instead of the reverse
     *                proxy needing to know which path is public
     */
    public void put(String bucket, String objectKey, InputStream stream, long size,
                    String contentType, Map<String, String> headers) {
        try {
            opsClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(stream, size, -1)
                    .contentType(contentType)
                    .headers(headers)
                    .build());
        } catch (Exception e) {
            throw new StorageException("Failed to store file", e);
        }
    }

    /**
     * The ONE deletion entry point, and the bucket is always explicit. Producers record it per row
     * ({@code item_files.bucket}, {@code item_files.published_bucket},
     * {@code storage_deletion_queue.bucket}) so an entry can outlive a bucket reconfiguration, and
     * so a private object and a published copy — now in two different buckets — cannot be removed
     * from the wrong one.
     */
    public void remove(String bucket, String objectKey) {
        try {
            opsClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new StorageException("Failed to delete stored file", e);
        }
    }

    public boolean exists(String bucket, String objectKey) {
        try {
            opsClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw new StorageException("Failed to check stored file", e);
        } catch (Exception e) {
            throw new StorageException("Failed to check stored file", e);
        }
    }

    /**
     * Reads a whole object into memory — <strong>the only READ operation in this adapter, and it
     * replaced a server-side {@code copyObject}</strong>.
     *
     * <p>Why the copy had to go: {@code copyObject} is byte-identical by definition, which is
     * exactly what made a published photo carry the camera's GPS coordinates. Stripping metadata
     * means rewriting the container, and rewriting means the bytes must pass through this JVM.
     *
     * <p><strong>What that costs, and why it is the exposure this service already had.</strong>
     * {@code MinioConfig}'s 60-second IO timeout is a socket-read timeout, so a black-holed MinIO
     * can hold the calling Tomcat worker for that long — the reason the SDK's five-minute default
     * was overridden. {@code put} on the upload path already carries that risk for the same number
     * of bytes, and both are on authenticated endpoints whose volume is bounded by an account's
     * plan. The anonymous board never reaches this method: it reads a URL out of a database row.
     *
     * @param maxBytes hard ceiling; an object larger than this is refused rather than allocated,
     *                 so a bucket written to out of band cannot exhaust a 768 MB container
     */
    public byte[] get(String bucket, String objectKey, int maxBytes) {
        try (InputStream in = opsClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            byte[] bytes = in.readNBytes(maxBytes);
            if (in.read() != -1) {
                throw new StorageException("Stored object is larger than " + maxBytes + " bytes", null);
            }
            return bytes;
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Failed to read stored file", e);
        }
    }

    /** Private objects only: a published copy needs no signature and is never presigned. */
    public String presignGet(String objectKey, Duration ttl) {
        try {
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new StorageException("Failed to create download link", e);
        }
    }
}
