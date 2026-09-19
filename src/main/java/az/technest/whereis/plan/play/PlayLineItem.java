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
 */
public record PlayLineItem(String productId, String basePlanId, Instant expiryTime,
                           boolean autoRenewing, boolean signupPromotion) {
}
