package az.technest.whereis.plan.play;

import java.time.Instant;

/**
 * One refunded or charged-back purchase, as {@code purchases.voidedpurchases.list} reports it.
 * Untrusted: it is what Google said, nothing more.
 *
 * @param purchaseToken the token that was voided
 * @param orderId       Google's order id; the row keeps it when it has none of its own
 * @param voidedAt      when Google says the void happened. Truthful beats convenient — the
 *                      reconciler's ordering depends on timestamps meaning what they say
 * @param voidedReason  Google's numeric reason (other / remorse / not received / defective / …)
 * @param voidedSource  who initiated it: the user, the developer, or Google. Logged with every
 *                      applied revoke, because an erroneous revoke has to be findable
 */
public record PlayVoidedPurchase(String purchaseToken, String orderId, Instant voidedAt,
                                 Integer voidedReason, Integer voidedSource) {
}
