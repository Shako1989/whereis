package az.technest.whereis.plan;

import az.technest.whereis.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
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
 * One Google purchase token, and what it currently entitles. V10.
 *
 * <p>{@code userId} and {@code purchaseToken} are {@code updatable = false} and have NO setters:
 * a token bound to one account can never be re-bound to another, which is one of the two
 * independent defences against a cross-account claim (the other is the
 * {@code obfuscatedExternalAccountId} cross-check at verification time). The second claimant gets
 * 409 {@code PLAN_PURCHASE_NOT_OWNED} and the first account's entitlement is untouched.
 *
 * <p>{@code lastEventTime} is deliberately NOT written by the verify endpoint — see the column
 * comment in V10. It is the RTDN handler's high-water mark and {@code null} means "no notification
 * has ever been applied to this row".
 */
@Entity
@Table(name = "user_subscriptions")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserSubscription extends AuditedEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID userId;

    /** Google's purchase token. Null only for a hand-written {@code OPERATOR} grant. */
    @Column(name = "purchase_token", updatable = false)
    @Setter(AccessLevel.NONE)
    private String purchaseToken;

    @Column(name = "product_id", length = 64)
    private String productId;

    /**
     * The tier this purchase bought, resolved from the catalog ONCE at verification time and frozen
     * here. Re-pointing {@code whereis.plans.*.product-id} later must never silently re-tier an
     * existing purchase.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Plan tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseProvenance provenance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SubscriptionState state;

    @Column(name = "entitled_until", nullable = false)
    private Instant entitledUntil;

    @Column(nullable = false)
    private boolean acknowledged;

    @Column(name = "linked_purchase_token")
    private String linkedPurchaseToken;

    @Column(name = "superseded_by")
    private UUID supersededBy;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "test_purchase", nullable = false)
    private boolean testPurchase;

    @Column(name = "latest_order_id", length = 64)
    private String latestOrderId;

    /** Epoch millis of the newest RTDN already applied; null until the handler ships and runs. */
    @Column(name = "last_event_time")
    private Long lastEventTime;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    /**
     * The entitlement rule applied to THIS row, for callers that already hold it (the verify
     * endpoint's cached-verdict short-circuit). It is the same four predicates the repository's
     * single {@code @Query} expresses; {@code PlanTierTransitionIT} pins the agreement.
     */
    public boolean entitlesAt(Instant now) {
        return entitledUntil != null
                && entitledUntil.isAfter(now)
                && voidedAt == null
                && supersededBy == null
                && state != null
                && state.entitles();
    }
}
