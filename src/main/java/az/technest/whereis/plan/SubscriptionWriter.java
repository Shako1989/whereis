package az.technest.whereis.plan;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every database write of the purchase-verification flow, each in its own transaction, reached by
 * {@code PurchaseVerificationService} through the Spring proxy. The orchestrator itself holds NO
 * transaction — the same shape as {@code AssistantService} — so the two Google calls sit strictly
 * BETWEEN transactions and never inside one (§6, enforced by ArchUnit).
 *
 * @see PurchaseVerificationService
 */
@Service
@RequiredArgsConstructor
public class SubscriptionWriter {

    private final UserSubscriptionRepository subscriptions;

    /**
     * What a verified purchase looks like once Google has answered. Every value here came from
     * Google or from {@link PlanCatalog}; nothing came from the request body except the token.
     *
     * <p>{@code lastEventTime} is absent on purpose and is never written by this flow: verifying a
     * purchase applies no RTDN, and seeding the high-water mark with "now" would make the handler
     * discard every notification already in flight for this purchase — see the column comment in
     * V10.
     */
    public record Snapshot(UUID userId, String purchaseToken, String productId, Plan tier,
                           PurchaseProvenance provenance, SubscriptionState state, Instant entitledUntil,
                           boolean acknowledged, String linkedPurchaseToken, boolean testPurchase,
                           String latestOrderId, Instant verifiedAt) {
    }

    /**
     * Insert the row, or refresh the caller's existing one.
     *
     * <p><strong>The ownership check is repeated here, and it has to be.</strong> The orchestrator
     * checked it before calling Google; a POST from a SECOND account can create the row during that
     * round trip, and this method would then happily update somebody else's row and answer the
     * caller 200 with a body showing FREE. ({@code user_id} is {@code updatable = false}, so
     * nothing escalates — but the caller is told a purchase was accepted that was never linked, and
     * the client marks the token sent and stops.) Found by {@code PlanPurchaseIT}'s two-account
     * race, not by reasoning about it.
     *
     * <p>A concurrent duplicate POST that loses the INSERT instead fails on
     * {@code ux_user_subscriptions_purchase_token} with a {@code DataIntegrityViolationException} at
     * commit. That catch deliberately does NOT live here: a constraint violation inside a JPA
     * transaction marks it rollback-only and leaves the persistence context unusable, so re-reading
     * and retrying in the same method cannot work. The orchestrator catches it outside any
     * transaction and calls {@link #refreshOwned} in a fresh one.
     */
    @Transactional
    public UserSubscription upsert(Snapshot snapshot) {
        return subscriptions.findByPurchaseToken(snapshot.purchaseToken())
                .map(existing -> apply(requireOwnedBy(existing, snapshot.userId()), snapshot))
                .orElseGet(() -> subscriptions.save(UserSubscription.builder()
                        .userId(snapshot.userId())
                        .purchaseToken(snapshot.purchaseToken())
                        .productId(snapshot.productId())
                        .tier(snapshot.tier())
                        .provenance(snapshot.provenance())
                        .state(snapshot.state())
                        .entitledUntil(snapshot.entitledUntil())
                        .acknowledged(snapshot.acknowledged())
                        .linkedPurchaseToken(snapshot.linkedPurchaseToken())
                        .testPurchase(snapshot.testPurchase())
                        .latestOrderId(snapshot.latestOrderId())
                        .verifiedAt(snapshot.verifiedAt())
                        .build()));
    }

    /**
     * The losing side of an INSERT race: a FRESH transaction that re-reads the row the winner
     * created and re-runs the ownership check verbatim before touching it.
     *
     * <p>The re-check is the point. The ownership check at step 3 ran before the Google round trip,
     * so a POST from a SECOND account can win the insert in between — the documented signed-out /
     * signed-in scenario racing the first account's own sync. Continuing past it would answer 200
     * to a caller whose purchase was never linked, and the client would mark the token sent.
     *
     * @throws PlanPurchaseNotOwnedException when the winning row belongs to somebody else
     */
    @Transactional
    public UserSubscription refreshOwned(Snapshot snapshot, UUID callerId) {
        UserSubscription row = subscriptions.findByPurchaseToken(snapshot.purchaseToken())
                .orElseThrow(() -> new IllegalStateException(
                        "The purchase token lost an insert race and then vanished"));
        return apply(requireOwnedBy(row, callerId), snapshot);
    }

    /** The one ownership rule, applied by both write paths. */
    private static UserSubscription requireOwnedBy(UserSubscription row, UUID callerId) {
        if (!row.getUserId().equals(callerId)) {
            throw new PlanPurchaseNotOwnedException("This purchase belongs to another account");
        }
        return row;
    }

    /**
     * REQUIRES_NEW rather than REQUIRED: there is no surrounding transaction today, but declaring
     * it keeps the write correct if a future caller ever wraps this flow — the same reasoning that
     * put REQUIRES_NEW on {@code AssistantMessageService#record}. A cross-bean call through the
     * proxy; a REQUIRES_NEW method on the orchestrator itself would be a self-invocation no-op.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAcknowledged(UUID subscriptionId) {
        subscriptions.findById(subscriptionId).ifPresent(row -> row.setAcknowledged(true));
    }

    /**
     * Everything Google may change about a known purchase. {@code userId} and {@code purchaseToken}
     * are NOT here and have no setters: a token bound to one account can never be re-bound.
     */
    private UserSubscription apply(UserSubscription row, Snapshot snapshot) {
        row.setProductId(snapshot.productId());
        row.setTier(snapshot.tier());
        row.setProvenance(snapshot.provenance());
        row.setState(snapshot.state());
        row.setEntitledUntil(snapshot.entitledUntil());
        row.setAcknowledged(snapshot.acknowledged());
        row.setLinkedPurchaseToken(snapshot.linkedPurchaseToken());
        row.setTestPurchase(snapshot.testPurchase());
        row.setLatestOrderId(snapshot.latestOrderId());
        row.setVerifiedAt(snapshot.verifiedAt());
        return row;
    }
}
