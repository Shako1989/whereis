package az.technest.whereis.plan.play;

import az.technest.whereis.plan.SubscriptionState;
import java.time.Instant;
import java.util.List;

/**
 * What {@code purchases.subscriptionsv2.get} said about one purchase token, with no Google type
 * leaking past the adapter. Immutable and UNTRUSTED — every cross-check in
 * {@code PurchaseVerificationService} reads this record and never the request body.
 *
 * @param state       mapped through {@link SubscriptionState#fromWire}; UNKNOWN for anything
 *                    unmapped, and UNKNOWN denies
 * @param rawState    exactly what Google said, for the ledger and for support
 * @param lineItems   never null; may be empty
 * @param startTime   when the subscription began
 * @param expiryTime  the LATEST line-item expiry, or null. Deliberately not what gets stored: with
 *                    more than one line item the aggregate over-entitles, so the verify endpoint
 *                    stores the MATCHED line item's own {@link PlayLineItem#expiryTime()}
 * @param obfuscatedExternalAccountId what the client set on {@code BillingFlowParams}, or null
 * @param acknowledged {@code acknowledgementState == ACKNOWLEDGED}
 * @param testPurchase a license-tester or closed-track purchase. Entitles, but is flagged
 * @param latestOrderId for support and for matching a voidedPurchaseNotification
 * @param linkedPurchaseToken the token this purchase replaces on an upgrade/downgrade, or null
 */
public record PlaySubscription(SubscriptionState state, String rawState, List<PlayLineItem> lineItems,
                               Instant startTime, Instant expiryTime, String obfuscatedExternalAccountId,
                               boolean acknowledged, boolean testPurchase, String latestOrderId,
                               String linkedPurchaseToken) {

    public PlaySubscription {
        lineItems = lineItems == null ? List.of() : List.copyOf(lineItems);
    }

    /**
     * Whether this purchase is a promo-code redemption. Defined over the LINE ITEMS, because that
     * is where {@code signupPromotion} lives — see {@link PlayLineItem}.
     */
    public boolean signupPromotion() {
        return lineItems.stream().anyMatch(PlayLineItem::signupPromotion);
    }
}
