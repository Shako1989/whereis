package az.technest.whereis.storage;

import io.minio.BucketExistsArgs;
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

    public void put(String objectKey, InputStream stream, long size, String contentType) {
        try {
            opsClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .stream(stream, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new StorageException("Failed to store file", e);
        }
    }

    public void remove(String objectKey) {
        remove(properties.bucket(), objectKey);
    }

    /** Outbox consumers pass the bucket recorded per entry — entries can outlive a bucket reconfiguration. */
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

    public boolean exists(String objectKey) {
        try {
            opsClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.bucket())
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
