package az.technest.whereis.plan.play;

import java.time.Instant;

/**
 * One line item of a {@code SubscriptionPurchaseV2}. Untrusted: it is what Google said, nothing
 * more.
 *
 * <p>{@code signupPromotion} lives HERE and not on {@link PlaySubscription} because that is where
 * it lives in the API: it is a field of {@code SubscriptionPurchaseLineItem}, at the same level as
 * {@code productId}, {@code expiryTime}, {@code autoRenewingPlan} and {@code offerDetails}. There
 * is no top-level getter to map, and it is the ONLY marker of a promo-code trial — during one,
 * {@code autoRenewingPlan.recurringPrice} reports the FULL price, so detecting a promotion by
 * {@code price == 0} finds nothing.
 *
 * @param productId       {@code SubscriptionPurchaseLineItem.productId}
 * @param basePlanId      {@code SubscriptionPurchaseLineItem.offerDetails.basePlanId} — NOT a
 *                        field of the line item itself
 * @param expiryTime      when THIS line item stops entitling; the value the verify endpoint stores
 *                        as {@code entitled_until} for the matched item, never the aggregate
 * @param autoRenewing    whether {@code autoRenewingPlan} is present and set to renew
 * @param signupPromotion {@code SubscriptionPurchaseLineItem.signupPromotion} — a promo-code
 *                        redemption
 * @param deferredProductId {@code SubscriptionPurchaseLineItem.deferredItemReplacement.productId},
 *                        or null. THE PRODUCT THIS LINE ITEM BECOMES WHEN THE TERM ENDS — a
 *                        DEFERRED downgrade keeps the OLD product on the token until then, so this
 *                        is the only thing that lets the plan screen say "Pro until 14 March, then
 *                        Standard" instead of "your plan changes at some point, we won't say to
 *                        what". Stored on {@code user_subscriptions.pending_product_id} as
 *                        GOOGLE'S ID, never as a tier: it is re-read on every refresh and resolved
 *                        through {@code PlanCatalog#tierOf} at READ time, so an id this deployment
 *                        does not configure reports a null pendingTier rather than a wrong one
 */
public record PlayLineItem(String productId, String basePlanId, Instant expiryTime,
                           boolean autoRenewing, boolean signupPromotion, String deferredProductId) {

    /** The five-argument form wave 1 used: no deferred replacement. */
    public PlayLineItem(String productId, String basePlanId, Instant expiryTime,
                        boolean autoRenewing, boolean signupPromotion) {
        this(productId, basePlanId, expiryTime, autoRenewing, signupPromotion, null);
    }
}
