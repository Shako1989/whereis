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
import org.hibernate.annotations.DynamicUpdate;
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
 * <p>{@code lastEventTime} is deliberately NOT written by the verify endpoint or the reconciler —
 * see the column comment in V10. It is the RTDN handler's high-water mark and {@code null} means
 * "no notification has ever been applied to this row".
 *
 * <p><strong>{@code @DynamicUpdate} is load-bearing, not a micro-optimisation.</strong> Four
 * writers touch this row — the verify endpoint, the RTDN handler, the reconciler and the voided
 * sweep — and each one deliberately leaves some columns alone ({@code voided_at} is write-once,
 * {@code last_event_time} moves only for a notification, {@code superseded_by} only for a link).
 * Without {@code @DynamicUpdate} Hibernate emits a FULL-COLUMN UPDATE, so a writer that only meant
 * to change {@code state} rewrites all three from whatever its own in-memory snapshot happened to
 * hold — and a chargeback applied by a concurrent revoke is silently undone by the next reconcile.
 */
@Entity
@Table(name = "user_subscriptions")
@DynamicUpdate
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
     * The product this subscription becomes when the current term ends — Google's
     * {@code lineItem.deferredItemReplacement.productId}, re-read on every refresh (V11).
     *
     * <p>Stored as GOOGLE'S PRODUCT ID and not as a tier, which is the exact opposite of the choice
     * made for {@link #tier} and deliberately so: {@code tier} is frozen at verification time
     * because re-pointing configuration must never re-tier a purchase somebody already paid for,
     * while this is a statement about a FUTURE that has not been paid for yet. The tier behind it
     * is resolved at READ time through {@code PlanCatalog#tierOf}, so an id this deployment does
     * not configure reports a null {@code pendingTier} rather than a wrong one.
     */
    @Column(name = "pending_product_id", length = 64)
    private String pendingProductId;

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
