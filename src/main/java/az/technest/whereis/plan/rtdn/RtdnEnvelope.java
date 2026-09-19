package az.technest.whereis.plan.rtdn;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/**
 * The Pub/Sub push envelope, exactly as it arrives on the wire:
 *
 * <pre>
 * { "message": { "messageId": "...", "message_id": "...", "data": "&lt;base64&gt;",
 *                "publishTime": "...", "publish_time": "...", "attributes": {...} },
 *   "subscription": "projects/&lt;p&gt;/subscriptions/&lt;s&gt;" }
 * </pre>
 *
 * <p>Pub/Sub sends BOTH the camelCase and the snake_case spelling of {@code messageId} and
 * {@code publishTime} depending on the encoding, which is why each carries a {@code @JsonAlias}.
 * Spring Boot leaves {@code FAIL_ON_UNKNOWN_PROPERTIES} off, so extra fields Google adds later are
 * ignored without a per-DTO annotation.
 */
public record RtdnEnvelope(Message message, String subscription) {

    /**
     * @param messageId   the dedup key, and the ledger's primary key
     * @param data        standard base64 of the {@code DeveloperNotification} JSON
     * @param publishTime when Pub/Sub published it; RFC 3339, may be absent on a hand-made probe
     * @param attributes  never read, kept so the raw envelope stored for a MALFORMED message is complete
     */
    public record Message(@JsonProperty("messageId") @JsonAlias("message_id") String messageId,
                          String data,
                          @JsonProperty("publishTime") @JsonAlias("publish_time") Instant publishTime,
                          Map<String, String> attributes) {
    }
}
