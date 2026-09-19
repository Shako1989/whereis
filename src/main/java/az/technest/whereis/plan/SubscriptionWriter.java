package az.technest.whereis.plan;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every database write of the billing flows — the verify endpoint, the RTDN handler, the reconciler
 * and the link resolver — each in its own transaction, reached by the orchestrators through the
 * Spring proxy. No orchestrator holds a transaction, so every Google call sits strictly BETWEEN
 * transactions and never inside one (§6, enforced by ArchUnit).
 *
 * <p><strong>ONE WRITER, ONE MAPPING.</strong> All three entry points build the same
 * {@link Snapshot} from Google's answer and apply it through the same private {@link #apply}. There
 * is no second mapping of a {@code SubscriptionPurchaseV2} onto a row, which is the first of the
 * four mechanisms that stop the handler and the reconciler from fighting.
 *
 * <p>The other three, each closing a different failure:
 * <ol>
 *   <li><strong>Compare-and-set on {@code verified_at}.</strong> {@link #reconcile} and
 *       {@link #applyNotification} both capture the row's {@code verified_at} BEFORE their Google
 *       round trip and write nothing if it has moved since — somebody else's answer is newer than
 *       ours, so ours is stale. The re-read takes {@code PESSIMISTIC_WRITE}, so the check and the
 *       write are atomic rather than check-then-act.</li>
 *   <li><strong>{@code last_event_time} is written by NOTHING but a notification.</strong> It is
 *       deliberately absent from {@link Snapshot} and set by a separate call, so no amount of
 *       verifying or reconciling can push the high-water mark ahead of notifications still in
 *       flight — the trap V10 documented for the verify endpoint, which applies here word for
 *       word.</li>
 *   <li><strong>{@code voided_at} is write-once</strong> and {@link #reconcile} refuses to run
 *       against a voided row. A chargeback that lands mid-round-trip must not be undone by a
 *       Google response that still says ACTIVE.</li>
 * </ol>
 *
 * <p>{@code UserSubscription} carries {@code @DynamicUpdate} for the same reason: without it
 * Hibernate emits a full-column UPDATE and a writer that only meant to touch {@code state} would
 * rewrite {@code voided_at}, {@code superseded_by} and {@code last_event_time} from whatever its
 * own snapshot happened to hold.
 *
 * @see PurchaseVerificationService
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionWriter {

    private final UserSubscriptionRepository subscriptions;

    /**
     * What a verified purchase looks like once Google has answered. Every value here came from
     * Google or from {@link PlanCatalog}; nothing came from the request body except the token.
     *
     * <p>{@code lastEventTime} is absent on purpose and is never written through this record:
     * verifying or reconciling a purchase applies no RTDN, and seeding the high-water mark with
     * "now" would make the handler discard every notification already in flight for this purchase
     * — see the column comment in V10. The RTDN handler sets it with {@link #advanceWatermark}
     * AFTER {@link #apply}, as a separate statement, precisely so it can never leak into a path
     * that did not apply a notification.
     *
     * <p>{@code pendingProductId} IS here, and has to be: {@link #apply} overwrites every mutable
     * field, so a component that the verify endpoint left off the record would be nulled out on
     * every app foreground ({@code PurchaseSyncer} posts the token every time). It is populated
     * from the MATCHED line item's {@code deferredItemReplacement.productId} on all three paths.
     */
    public record Snapshot(UUID userId, String purchaseToken, String productId, Plan tier,
                           PurchaseProvenance provenance, SubscriptionState state, Instant entitledUntil,
                           boolean acknowledged, String linkedPurchaseToken, boolean testPurchase,
                           String latestOrderId, Instant verifiedAt, String pendingProductId) {
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
     * and retrying in the same method cannot work. The caller catches it outside any transaction
     * and calls {@link #refreshOwned} in a fresh one — the RTDN attribution path included.
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
                        .pendingProductId(snapshot.pendingProductId())
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

    /**
     * THE RTDN REFRESH WRITE: lock the row, re-check BOTH guards, apply Google's answer, advance
     * the watermark.
     *
     * <p>Two independent guards, because they catch two different races and each is blind to the
     * other:
     * <ul>
     *   <li><strong>the monotonic watermark</strong>, strictly {@code >}. Pub/Sub does not
     *       guarantee order, so an older event arriving after a newer one is normal traffic. Equal
     *       is discarded too, and that is load-bearing: two concurrent deliveries of the same
     *       message carry the same {@code eventTimeMillis}, so the second is discarded by the guard
     *       instead of re-applying.</li>
     *   <li><strong>compare-and-set on {@code verified_at}</strong>. The reconciler deliberately
     *       never moves the watermark, so without this a handler whose {@code play.get()} happened
     *       BEFORE a reconcile could commit afterwards and resurrect an expired row as ACTIVE with
     *       a stale expiry — the watermark check would pass, because nothing moved it.</li>
     * </ul>
     *
     * <p>The lock is {@code PESSIMISTIC_WRITE} (the {@code moveItem} pattern) and both checks run
     * INSIDE this transaction. Evaluating either outside and writing inside is a lost-update race
     * the width of a Google round trip.
     *
     * @param expectedVerifiedAt the row's {@code verified_at} as it was read BEFORE the Google call
     * @return whether anything was written
     */
    @Transactional
    public boolean applyNotification(UUID subscriptionId, Snapshot snapshot, long eventTimeMillis,
                                     Instant expectedVerifiedAt) {
        UserSubscription row = subscriptions.findForUpdate(subscriptionId)
                .orElse(null);
        if (row == null) {
            return false;
        }
        Long watermark = row.getLastEventTime();
        if (watermark != null && eventTimeMillis <= watermark) {
            return false;
        }
        if (!java.util.Objects.equals(row.getVerifiedAt(), expectedVerifiedAt)) {
            // Somebody committed a newer answer while we were talking to Google. Theirs wins.
            return false;
        }
        apply(row, snapshot);
        // A SEPARATE statement, never a Snapshot component: only a notification may move this.
        row.setLastEventTime(Math.max(watermark == null ? Long.MIN_VALUE : watermark, eventTimeMillis));
        return true;
    }

    /**
     * Advance the per-row high-water mark and nothing else. Used by the REVOKE path, which writes
     * {@code voided_at} from the notification alone and must still record that the notification was
     * applied.
     *
     * <p>{@code max(existing, eventTimeMillis)} rather than a plain assignment, so a revoke that
     * legitimately carries an OLDER event time (a refund and its accompanying cancellation are
     * emitted milliseconds apart and Pub/Sub guarantees no order) cannot rewind the watermark for
     * the refresh path.
     */
    @Transactional
    public void advanceWatermark(UUID subscriptionId, long eventTimeMillis) {
        subscriptions.findForUpdate(subscriptionId).ifPresent(row -> {
            Long watermark = row.getLastEventTime();
            if (watermark == null || eventTimeMillis > watermark) {
                row.setLastEventTime(eventTimeMillis);
            }
        });
    }

    /**
     * THE REVOKE. Write-once {@code voided_at}, and NOTHING else that a refund does not actually
     * tell us.
     *
     * <p><strong>Deliberately not guarded by the watermark.</strong> A refund and its accompanying
     * {@code SUBSCRIPTION_CANCELED} are emitted within milliseconds of each other and Pub/Sub
     * guarantees no ordering; if the cancellation lands first the watermark advances, and a
     * watermark-guarded revoke would be discarded as stale — leaving the row {@code CANCELED} with
     * a future expiry, which is one of the three ENTITLING states. A refunded annual subscriber
     * would keep PRO until the six-hourly sweep happened to repair it. Write-once already provides
     * everything the watermark would have: a second delivery, the sweep finding the same purchase,
     * and a {@code SUBSCRIPTION_REVOKED} for the same refund are all normal and none of them may
     * move the timestamp.
     *
     * <p>{@code state}, {@code tier}, {@code entitled_until} and {@code verified_at} are NOT
     * touched. We did not ask Google anything, so we may not claim to have verified anything;
     * {@code verified_at} is the reconciler's compare-and-set token and corrupting it here is how
     * the two components start fighting.
     *
     * <p>The entitlement ends INSTANTLY with no further code, because {@code voided_at IS NULL} is
     * already one of the four predicates in {@code UserSubscriptionRepository#entitlingOf}.
     *
     * @param eventTimeMillis the notification's event time, or {@code null} when this came from the
     *                        voided-purchases SWEEP — which applies no notification and therefore
     *                        must not touch the watermark at all
     * @return whether this call is the one that voided the row (false = already voided, or gone)
     */
    @Transactional
    public boolean markVoided(UUID subscriptionId, Instant voidedAt, String orderId, Long eventTimeMillis) {
        UserSubscription row = subscriptions.findForUpdate(subscriptionId)
                .orElse(null);
        if (row == null) {
            return false;
        }
        boolean firstVoid = row.getVoidedAt() == null;
        if (firstVoid) {
            row.setVoidedAt(voidedAt);
            if (row.getLatestOrderId() == null && orderId != null) {
                row.setLatestOrderId(orderId);
            }
        }
        if (eventTimeMillis != null) {
            Long watermark = row.getLastEventTime();
            if (watermark == null || eventTimeMillis > watermark) {
                row.setLastEventTime(eventTimeMillis);
            }
        }
        return firstVoid;
    }

    /**
     * Point the replaced half of an upgrade at its replacement. The superseded row stops entitling
     * immediately — {@code supersededBy IS NULL} is the fourth predicate in {@code entitlingOf}.
     *
     * <p>Re-reads inside this transaction and re-checks BOTH the same-user rule and
     * {@code supersededBy == null}: {@code ux_user_subscriptions_supersedes} is UNIQUE, so a
     * concurrent writer can win this race, and the composite self-FK
     * {@code (superseded_by, user_id) -> (id, user_id)} makes a cross-user link a hard error at
     * commit rather than a silent corruption. The resulting
     * {@code DataIntegrityViolationException} is caught OUTSIDE this transaction by
     * {@code SubscriptionLinkResolver}, the same discipline
     * {@code PurchaseVerificationService#persist} uses and for the same reason.
     *
     * <p>Touches neither {@code verified_at} nor {@code tier}/{@code state}, so it can never lose a
     * race with the other two writers.
     */
    @Transactional
    public boolean markSuperseded(UUID oldId, UUID newId) {
        UserSubscription old = subscriptions.findForUpdate(oldId).orElse(null);
        UserSubscription replacement = subscriptions.findById(newId).orElse(null);
        if (old == null || replacement == null || old.getSupersededBy() != null
                || !old.getUserId().equals(replacement.getUserId()) || oldId.equals(newId)) {
            return false;
        }
        old.setSupersededBy(newId);
        return true;
    }

    /**
     * THE RECONCILER'S WRITE. Everything {@link #applyNotification} does except the watermark,
     * which the reconciler must never move: it applies no notification, and writing it would push
     * the high-water mark ahead of notifications still in flight and the handler would discard them
     * all, silently and unrecoverably.
     *
     * <p>The compare-and-set on {@code verified_at} is atomic, not check-then-act: the re-read
     * takes {@code PESSIMISTIC_WRITE}, so a concurrent RTDN REVOKE either commits before this
     * transaction reads the row (and is seen) or blocks behind it. Without the lock, this UPDATE
     * would simply wait for the revoke to commit and then write {@code voided_at = NULL} back from
     * its own stale in-memory snapshot — a chargeback silently undone.
     *
     * <p>It also refuses outright to run against a voided or superseded row, which is the fourth
     * anti-fight mechanism.
     *
     * @return whether anything was written
     */
    @Transactional
    public boolean reconcile(UUID subscriptionId, Snapshot snapshot, Instant expectedVerifiedAt) {
        UserSubscription row = subscriptions.findForUpdate(subscriptionId)
                .orElse(null);
        if (row == null || row.getVoidedAt() != null || row.getSupersededBy() != null) {
            return false;
        }
        if (!java.util.Objects.equals(row.getVerifiedAt(), expectedVerifiedAt)) {
            return false;
        }
        apply(row, snapshot);
        return true;
    }

    /** Bump {@code verified_at} and nothing else: Google answered definitively, but said nothing new. */
    @Transactional
    public void touchVerifiedAt(UUID subscriptionId, Instant verifiedAt) {
        subscriptions.findById(subscriptionId).ifPresent(row -> row.setVerifiedAt(verifiedAt));
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

    /** The row, in a read-only transaction, for callers that need its pre-round-trip state. */
    @Transactional(readOnly = true)
    public Optional<UserSubscription> find(UUID subscriptionId) {
        return subscriptions.findById(subscriptionId);
    }

    /**
     * Everything Google may change about a known purchase. {@code userId} and {@code purchaseToken}
     * are NOT here and have no setters: a token bound to one account can never be re-bound.
     *
     * <p>{@code voidedAt} is NOT here either, and that is the other half of write-once: a
     * {@code SUBSCRIPTION_RENEWED} arriving after a chargeback must never silently un-refund the
     * account.
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
        row.setPendingProductId(snapshot.pendingProductId());
        return row;
    }
}
