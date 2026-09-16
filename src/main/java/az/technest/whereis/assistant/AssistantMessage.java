package az.technest.whereis.assistant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * One assistant request as the user typed it, with what the model made of it (V8). Written once,
 * never updated — there is no {@code updated_at}, so this deliberately does not extend
 * {@code AuditedEntity}. The sentence is DATA here, kept for the life of the account; it must still
 * never appear in a log line above DEBUG.
 *
 * <p>{@code interpretation} is a typed record behind {@code @JdbcTypeCode(SqlTypes.JSON)} on
 * purpose: a {@code String} mapped the same way is routed through the JSON format mapper and lands
 * in the column as a double-encoded scalar, which silently breaks every
 * {@code interpretation->>'…'} query ({@code AssistantMessageIT} asserts the shape).
 */
@Entity
@Table(name = "assistant_messages")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AssistantMessage {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssistantMode mode;

    /** {@code RememberRequest}'s cap; SEARCH's 500 is enforced by {@code AssistantService.sanitize}. */
    @Column(nullable = false, length = 1000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AssistantOutcome outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private InterpretationSnapshot interpretation;

    /** {@code ErrorCode} name or {@code UNEXPECTED_ERROR}; set only when {@code outcome = FAILED}. */
    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "prompt_version", nullable = false, length = 64)
    private String promptVersion;

    /** Three decimals in [0,1] ({@code InterpretationSnapshots.confidenceOf}); NULL when the provider gave none. */
    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    /** Set only when {@code outcome = CREATED}; the FK is ON DELETE SET NULL. */
    @Column(name = "item_id")
    private UUID itemId;

    /** The resolved space, when any; the FK is ON DELETE SET NULL. */
    @Column(name = "space_id")
    private UUID spaceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
