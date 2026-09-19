package az.technest.whereis.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * Outbox for Google Play subscription cancellations (V11) — a line-for-line sibling of
 * {@code StorageDeletionQueueEntry}, down to the {@code @PrePersist}.
 *
 * <p>That is the point rather than a coincidence: the codebase already has exactly ONE correct
 * answer for "commit with the database, then act on an external system that cannot join the
 * transaction" (V6's {@code storage_deletion_queue} + {@code StorageJanitor}), and inventing a
 * second one here would be the only unfamiliar thing in this wave.
 *
 * <p><strong>No {@code user_id} and no foreign key</strong>, and that IS the design: the user row is
 * gone by the time {@code PlayCancellationJanitor} reads this table. A FK would make the queue
 * undrainable in exactly the case it exists for.
 */
@Entity
@Table(name = "play_cancellation_queue")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PlayCancellationQueueEntry {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    /** A bearer credential: never logged above DEBUG — log {@link PurchaseTokens#digest} instead. */
    @Column(name = "purchase_token", nullable = false, columnDefinition = "text")
    private String purchaseToken;

    /** {@code purchases.subscriptions.cancel} is the v1 endpoint and needs it. */
    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;

    /** Why this row exists; {@code ACCOUNT_DELETED} is the only value today. */
    @Column(nullable = false, length = 32)
    private String reason;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        if (this.nextAttemptAt == null) {
            this.nextAttemptAt = this.createdAt;
        }
    }
}
