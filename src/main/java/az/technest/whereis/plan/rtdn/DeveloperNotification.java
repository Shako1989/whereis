package az.technest.whereis.plan.rtdn;

import java.util.Map;

/**
 * Google's {@code DeveloperNotification}, the base64-decoded contents of {@code message.data}:
 *
 * <pre>
 * { "version":"1.0", "packageName":"az.technest.whereis", "eventTimeMillis":"1503349566168",
 *   "subscriptionNotification": {...} | "voidedPurchaseNotification": {...}
 *   | "oneTimeProductNotification": {...} | "testNotification": {...} }
 * </pre>
 *
 * <p><strong>The four notification fields are SIBLINGS, not variants.</strong> Read all four, take
 * the first populated one, and when more than one is populated (Google does not do this, but the
 * shape permits it) handle {@code voidedPurchaseNotification} FIRST — a revocation must never lose
 * to a state refresh.
 *
 * <p>{@code eventTimeMillis} arrives as a STRING (it is a JSON int64), which is why it is typed as
 * one here and parsed by the handler; an unparseable value is
 * {@link PlayNotificationOutcome#MALFORMED} rather than a 500.
 */
public record DeveloperNotification(String version, String packageName, String eventTimeMillis,
                                    SubscriptionNotification subscriptionNotification,
                                    VoidedPurchaseNotification voidedPurchaseNotification,
                                    OneTimeProductNotification oneTimeProductNotification,
                                    Map<String, Object> testNotification) {

    /**
     * @param version        Google's notification version
     * @param notificationType the {@code SubscriptionNotificationType} integer; see
     *                       {@link SubscriptionNotificationType}
     * @param purchaseToken  the token this notification is about
     * @param subscriptionId Google's product id. <strong>Never used to decide a tier</strong> — the
     *                       tier comes from the line item {@code subscriptionsv2.get} returns.
     */
    public record SubscriptionNotification(String version, Integer notificationType,
                                           String purchaseToken, String subscriptionId) {
    }

    /**
     * A refund or a chargeback.
     *
     * @param purchaseToken the token that was voided
     * @param orderId       Google's order id, for support and for matching
     * @param productType   1 = subscription, 2 = one-time product. <strong>Note the inversion with
     *                      {@code purchases.voidedpurchases.list}'s own {@code type} parameter,
     *                      where 0 = one-time only and 1 = one-time AND subscriptions.</strong>
     *                      Two Google fields, the same numbers, opposite meanings.
     * @param refundType    1 = full, 2 = quantity-based partial (one-time products only)
     */
    public record VoidedPurchaseNotification(String purchaseToken, String orderId,
                                             Integer productType, Integer refundType) {
    }

    /** whereis sells none; recorded in full so that if it ever does, the history is there. */
    public record OneTimeProductNotification(String version, Integer notificationType,
                                             String purchaseToken, String sku) {
    }

    /**
     * Which sibling is populated, with {@code voidedPurchaseNotification} first because a
     * revocation must never lose to a state refresh.
     */
    public PlayNotificationKind kind() {
        if (voidedPurchaseNotification != null) {
            return PlayNotificationKind.VOIDED_PURCHASE;
        }
        if (subscriptionNotification != null) {
            return PlayNotificationKind.SUBSCRIPTION;
        }
        if (oneTimeProductNotification != null) {
            return PlayNotificationKind.ONE_TIME_PRODUCT;
        }
        if (testNotification != null) {
            return PlayNotificationKind.TEST;
        }
        return PlayNotificationKind.UNKNOWN;
    }
}
