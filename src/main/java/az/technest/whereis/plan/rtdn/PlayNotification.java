package az.technest.whereis.plan.rtdn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One Pub/Sub delivery of a Google Play real-time developer notification, and what this server did
 * with it. V10 created the table and deliberately left it unmapped; V11 pins its two enums and the
 * handler ships, so it becomes a mapped entity and Hibernate {@code validate} starts checking it.
 *
 * <p><strong>The primary key is Google's message id, with no {@code @GeneratedValue}.</strong> That
 * is the whole dedup design: Pub/Sub guarantees at-least-once delivery, so insert-first /
 * process-after turns a redelivery into a primary-key collision rather than an application-level
 * "have I seen this?" query whose answer can be stale. It is also why the insert must go through
 * {@code EntityManager#persist} and never {@code save()} — {@code save()} on a detached entity with
 * an assigned id performs a MERGE (SELECT then UPDATE) and would silently overwrite the first
 * delivery's bookkeeping instead of colliding.
 *
 * <p>{@code payload} is a {@code Map<String, Object>} behind {@code @JdbcTypeCode(SqlTypes.JSON)}
 * and never a {@code String}: V8's lesson applies verbatim — a {@code String} field behind the JSON
 * type serialises to a double-encoded JSON <em>scalar</em>, not to an object, and the ledger would
 * then be unusable for the one thing it exists for. For {@link PlayNotificationOutcome#MALFORMED}
 * it holds the raw Pub/Sub envelope rather than the decoded notification, because on that path
 * nothing decoded (see V11).
 *
 * <p>No {@code user_id} and no foreign key, by V10's design: a notification can legitimately arrive
 * for a token this server has never seen.
 */
@Entity
@Table(name = "play_notifications")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PlayNotification {

    @Id
    @Column(name = "message_id", length = 128, updatable = false)
    @Setter(AccessLevel.NONE)
    private String messageId;

    @Column(name = "publish_time", nullable = false)
    private Instant publishTime;

    /** Null ONLY for a MALFORMED row, where it could not be parsed (V11's CHECK). */
    @Column(name = "event_time_millis")
    private Long eventTimeMillis;

    /** Null ONLY for a MALFORMED row (V11's CHECK). */
    @Column(name = "package_name", length = 128)
    private String packageName;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_kind", nullable = false, length = 32)
    private PlayNotificationKind notificationKind;

    /** Google's numeric subtype: {@code subscriptionNotificationType}, or the voided productType. */
    @Column(name = "notification_type")
    private Integer notificationType;

    @Column(name = "purchase_token", columnDefinition = "text")
    private String purchaseToken;

    @Column(name = "product_id", length = 64)
    private String productId;

    @Column(name = "order_id", length = 64)
    private String orderId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /** SUCCEEDED. Null while pending, and permanently null for MALFORMED and FAILED. */
    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "processing_error", length = 500)
    private String processingError;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private PlayNotificationOutcome outcome;
}
