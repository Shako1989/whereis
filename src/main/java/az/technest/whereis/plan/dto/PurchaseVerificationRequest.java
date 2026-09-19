package az.technest.whereis.plan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/v1/users/me/plan/purchases}.
 *
 * <p><strong>Neither field is trusted.</strong> Tier, state, expiry, acknowledgement, test flag and
 * promo marker all come from Google's response, every time; the request contributes only the token
 * and a product-id CLAIM, and the tier is resolved from Google's own line item. A client cannot
 * upgrade itself by lying about {@code productId}.
 *
 * @param purchaseToken Google's opaque purchase token. The bound is 2000 rather than the column's
 *                      width (the column is {@code text}) so that an over-long token from a vendor
 *                      change is still a bounded body rather than an unbounded one — Google
 *                      documents no maximum, and observed tokens are around 250 characters
 * @param productId     the product the client believes it bought. Sent even though
 *                      {@code subscriptionsv2.get} does not need it, for two reasons:
 *                      {@code purchases.subscriptions.acknowledge} DOES need it, and having the
 *                      claim lets the server cross-check it against Google's line items instead of
 *                      trusting either side
 */
public record PurchaseVerificationRequest(
        @NotBlank @Size(max = 2000) String purchaseToken,
        @NotBlank @Pattern(regexp = "^[a-z0-9_.]{1,64}$") String productId) {
}
