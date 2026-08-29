package az.technest.whereis.storage;

import io.minio.MinioClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public ApplicationRunner minioBucketInitializer(MinioAdapter adapter) {
        return args -> adapter.ensureBucket();
    }
}
