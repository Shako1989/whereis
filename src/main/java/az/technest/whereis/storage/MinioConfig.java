package az.technest.whereis.storage;

import io.minio.MinioClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class MinioConfig {

    // An explicit region is set on both clients so the SDK never attempts a server-side
    // region lookup — critical for the presign client, whose endpoint is the browser-facing
    // host and may not be reachable from inside the backend container at all.
    private static final String REGION = "us-east-1";

    // The SDK default is 5 MINUTES for connect/read/write — a black-holed MinIO would pin
    // Tomcat workers for that long and turn a storage outage into a full API outage.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration IO_TIMEOUT = Duration.ofSeconds(60);

    @Bean
    public MinioClient minioOpsClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .region(REGION)
                .httpClient(boundedHttpClient())
                .build();
    }

    @Bean
    public MinioClient minioPresignClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.presignEndpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .region(REGION)
                .httpClient(boundedHttpClient())
                .build();
    }

    private static OkHttpClient boundedHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(IO_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(IO_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    @Bean
    public ApplicationRunner minioBucketInitializer(MinioAdapter adapter, MinioProperties properties) {
        return args -> {
            adapter.ensureBucket();
            announcePublishedPhotoMode(adapter, properties);
        };
    }

    /**
     * Announces which of the two published-photo modes this process is in, ONCE, at startup — the
     * shape {@code PlayConfig} uses for disabled billing, and for the same reason: a deliberate
     * configuration that logged a warning every request would train an operator to ignore the log.
     *
     * <p><strong>The public bucket is never created here.</strong> Creating it would create it
     * without the anonymous-read policy, and every published image would then 403 with nothing
     * saying why — a quieter failure than the one this guards. So a named-but-absent bucket is a
     * WARN naming the operator step, and publishing fails loudly per request (502 STORAGE_ERROR)
     * rather than falling back to anything. An UNSET bucket is not a failure at all: listings
     * publish and the board serves them, without images.
     */
    private static void announcePublishedPhotoMode(MinioAdapter adapter, MinioProperties properties) {
        if (!properties.publishedPhotosEnabled()) {
            log.info("Published marketplace photos are DISABLED: minio.public-bucket "
                    + "(MINIO_PUBLIC_BUCKET) is not set. Listings publish and the board serves them "
                    + "with no image; nothing else is affected. deploy/README.md Step 4b creates the "
                    + "bucket and switches this on with one environment variable.");
            return;
        }
        if (!adapter.bucketExists(properties.publicBucket())) {
            log.warn("minio.public-bucket '{}' is configured but DOES NOT EXIST. Publishing a "
                    + "listing will fail until it is created and made anonymously readable "
                    + "(deploy/README.md Step 4b). This application deliberately does not create "
                    + "it: it cannot grant the anonymous-read policy that makes it useful.",
                    properties.publicBucket());
            return;
        }
        log.info("Published marketplace photos are enabled: bucket '{}', permanent public URLs "
                + "under {}, camera metadata stripped before every publish.",
                properties.publicBucket(), properties.presignEndpoint());
    }
}
