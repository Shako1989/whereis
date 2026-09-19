package az.technest.whereis.plan;

import az.technest.whereis.plan.play.PlayLineItem;
import az.technest.whereis.plan.play.PlaySubscription;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Maps Google's answer about a purchase onto a {@link SubscriptionWriter.Snapshot}, for the two
 * paths that REFRESH a row this server already knows: the RTDN handler and the reconciler.
 *
 * <p>It exists so those two cannot disagree. {@code PurchaseVerificationService} builds the same
 * fields with one extra rule of its own — it prefers the product the CLIENT claimed, which only
 * makes sense when there is a client — and everything after that is this class's rules.
 *
 * <p><strong>The decision worth reading: a refresh that matches NO offered line item keeps the
 * row's frozen tier and product.</strong> It does not throw and it does not re-tier. Three ordinary
 * situations produce it — {@code SUBSCRIPTION_EXPIRED} and {@code SUBSCRIPTION_REVOKED} are exactly
 * when {@code subscriptionsv2.get} is most likely to return no usable line item, and a re-pointed
 * {@code whereis.plans.*.product-id} does it for a live row — and {@code tier} is denormalized on
 * {@code user_subscriptions} precisely so it is never re-derived. The alternative, which the first
 * draft of this wave had, was {@code PlayProductMismatchException}: a 400 the RTDN ledger never
 * recorded, retried by Pub/Sub for seven days, and a reconciler batch that aborted on the same row
 * every fifteen minutes forever because {@code verified_at} never moved.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionSnapshots {

    private final PlanCatalog catalog;

    /**
     * Google's answer, mapped onto the row it is about.
     *
     * @param existing the row being refreshed; its {@code tier}, {@code productId} and, in the last
     *                 resort, its {@code entitledUntil} are what survive when Google names nothing
     *                 this deployment offers
     */
    public SubscriptionWriter.Snapshot refreshOf(UserSubscription existing, PlaySubscription google,
                                                 Instant verifiedAt) {
        Optional<PlayLineItem> matched = bestOffered(google);
        Plan tier = matched.flatMap(item -> catalog.tierOf(item.productId())).orElse(existing.getTier());
        String productId = matched.map(PlayLineItem::productId).orElse(existing.getProductId());
        return new SubscriptionWriter.Snapshot(
                existing.getUserId(),
                existing.getPurchaseToken(),
                productId,
                tier,
                google.signupPromotion() ? PurchaseProvenance.PROMO_CODE : PurchaseProvenance.PLAY_PURCHASE,
                google.state(),
                expiryOf(matched, google, existing, verifiedAt),
                google.acknowledged(),
                google.linkedPurchaseToken(),
                google.testPurchase(),
                google.latestOrderId() == null ? existing.getLatestOrderId() : google.latestOrderId(),
                verifiedAt,
                // Not `matched.map(...).orElse(...)`: Optional.map on a null component yields
                // EMPTY, so that form would silently keep a pending product Google has stopped
                // reporting. A matched line item with no deferred replacement CLEARS it — this is
                // a statement about a future that has not been paid for, and it is allowed to
                // change on every refresh, unlike the frozen `tier`.
                matched.isPresent() ? matched.get().deferredProductId() : existing.getPendingProductId());
    }

    /**
     * The snapshot for a row that does not exist yet — the RTDN attribution path, where an upgrade's
     * notification beats the client's foreground sync. There is no frozen tier to fall back on, so
     * a purchase offering none of our products cannot be created at all and this answers empty.
     */
    public Optional<SubscriptionWriter.Snapshot> creationOf(UUID userId, String purchaseToken,
                                                            PlaySubscription google, Instant verifiedAt) {
        Optional<PlayLineItem> matched = bestOffered(google);
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        PlayLineItem lineItem = matched.get();
        Plan tier = catalog.tierOf(lineItem.productId()).orElseThrow();
        Instant expiry = lineItem.expiryTime() == null ? google.expiryTime() : lineItem.expiryTime();
        return Optional.of(new SubscriptionWriter.Snapshot(
                userId,
                purchaseToken,
                lineItem.productId(),
                tier,
                google.signupPromotion() ? PurchaseProvenance.PROMO_CODE : PurchaseProvenance.PLAY_PURCHASE,
                google.state(),
                // No expiry from Google means store the instant we asked: truthful, already in the
                // past, and therefore already non-entitling. The fail-closed direction.
                expiry == null ? verifiedAt : expiry,
                google.acknowledged(),
                google.linkedPurchaseToken(),
                google.testPurchase(),
                google.latestOrderId(),
                verifiedAt,
                lineItem.deferredProductId()));
    }

    /**
     * The MATCHED line item's own expiry, never the aggregate — with more than one line item the
     * aggregate over-entitles.
     *
     * <p>The last fallback is the subtle one. When Google offers no readable expiry AND still
     * reports an entitling state, the row keeps the term it already had: zeroing it would end a
     * paid subscription because we could not read a timestamp, which is the wrong direction to
     * fail. When the state does NOT entitle, {@code verifiedAt} is stored — truthful and already in
     * the past.
     */
    private static Instant expiryOf(Optional<PlayLineItem> matched, PlaySubscription google,
                                    UserSubscription existing, Instant verifiedAt) {
        Instant expiry = matched.map(PlayLineItem::expiryTime).orElse(google.expiryTime());
        if (expiry != null) {
            return expiry;
        }
        return google.state().entitles() ? existing.getEntitledUntil() : verifiedAt;
    }

    /**
     * The HIGHEST-tier line item this application actually offers, or empty.
     *
     * <p>Highest rather than first, for the reason {@code PurchaseVerificationService} already
     * records: Google does not promise the order of {@code lineItems}, so "first" would let the
     * same purchase record a different tier on a retry, and every candidate here is a product the
     * user genuinely bought.
     */
    public Optional<PlayLineItem> bestOffered(PlaySubscription google) {
        return google.lineItems().stream()
                .filter(item -> catalog.tierOf(item.productId()).isPresent())
                .max(Comparator.comparing(item -> catalog.tierOf(item.productId()).orElseThrow()));
    }
}
