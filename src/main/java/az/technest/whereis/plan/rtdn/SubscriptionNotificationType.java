package az.technest.whereis.plan.rtdn;

/**
 * Google's {@code SubscriptionNotificationType} integers, and the ONE decision this application
 * makes about each of them.
 *
 * <p><strong>The governing decision: the notification is a trigger and an ordering token, not a
 * fact.</strong> A {@code subscriptionNotification} carries only {@code notificationType},
 * {@code purchaseToken} and {@code subscriptionId} — not the state, the expiry, the acknowledgement
 * flag, the tier or the linked token. So the handler does not write state from the notification. On
 * every type except one it calls {@code purchases.subscriptionsv2.get} and writes what Google says,
 * through the same {@code SubscriptionWriter.Snapshot} the verify endpoint builds. One writer, one
 * mapping, one source of truth.
 *
 * <p>Three consequences, and they are what makes this an enum of two values instead of fifteen
 * bespoke branches:
 * <ul>
 *   <li>a type this build has never heard of is NOT ignored — the default is {@link #REFRESH}, so
 *       "a missing type is a silent bug" stops being true for anything Google adds later;</li>
 *   <li>two types that mean the same thing to us ({@code SUBSCRIPTION_RECOVERED} and
 *       {@code SUBSCRIPTION_RESTARTED}) are not two code paths;</li>
 *   <li>the handler never has to know which replacement mode the client used.</li>
 * </ul>
 *
 * <p><strong>The one exception is revocation</strong>, and it is an exception on purpose:
 * {@code SUBSCRIPTION_REVOKED} (12) is applied from the notification alone with NO Google call, so
 * a refund is never blocked by a Play API outage. Being unable to reach Google must not be able to
 * keep a refunded user entitled.
 */
public enum SubscriptionNotificationType {

    /** Recovery from hold: back to ACTIVE with a new expiry. */
    SUBSCRIPTION_RECOVERED(1, RtdnAction.REFRESH),
    /** A renewal extended the term and produced a new order id. */
    SUBSCRIPTION_RENEWED(2, RtdnAction.REFRESH),
    /**
     * Auto-renew is off; the paid term is NOT over. {@code entitled_until} does not move and
     * {@code SubscriptionState.entitles()} includes CANCELED for exactly this reason — cutting
     * access here would be taking away something already paid for.
     */
    SUBSCRIPTION_CANCELED(3, RtdnAction.REFRESH),
    /** A new purchase, or the new half of an upgrade. Followed by the link resolver. */
    SUBSCRIPTION_PURCHASED(4, RtdnAction.REFRESH),
    /** Google could not take the payment. Does NOT entitle; nothing is deleted, only creation refused. */
    SUBSCRIPTION_ON_HOLD(5, RtdnAction.REFRESH),
    /** The window in which the user fixes their card WITHOUT losing access. Still entitles. */
    SUBSCRIPTION_IN_GRACE_PERIOD(6, RtdnAction.REFRESH),
    SUBSCRIPTION_RESTARTED(7, RtdnAction.REFRESH),
    /** Price is not our concern — we sell tiers, and the tier did not change. */
    SUBSCRIPTION_PRICE_CHANGE_CONFIRMED(8, RtdnAction.REFRESH),
    /** A developer-initiated defer extends the term; {@code pending_product_id} is re-read. */
    SUBSCRIPTION_DEFERRED(9, RtdnAction.REFRESH),
    /** A paused subscription is not being paid for. Does NOT entitle. */
    SUBSCRIPTION_PAUSED(10, RtdnAction.REFRESH),
    /** Only SCHEDULED. Handling it is the point: doing nothing would look like an actual pause. */
    SUBSCRIPTION_PAUSE_SCHEDULE_CHANGED(11, RtdnAction.REFRESH),
    /**
     * The one REVOKE. {@code voided_at IS NULL} is one of the four entitlement predicates, so the
     * account drops on the very next request without waiting for an expiry.
     */
    SUBSCRIPTION_REVOKED(12, RtdnAction.REVOKE),
    /** Belt-and-braces: {@code entitled_until > now()} would have ended it anyway. */
    SUBSCRIPTION_EXPIRED(13, RtdnAction.REFRESH),
    SUBSCRIPTION_PRICE_CHANGE_UPDATED(19, RtdnAction.REFRESH),
    /** A deferred-payment purchase that was never completed. It never entitled. */
    SUBSCRIPTION_PENDING_PURCHASE_CANCELED(20, RtdnAction.REFRESH);

    /** What the handler does with a notification of this type. */
    public enum RtdnAction {
        /** Ask Google and write what it says. */
        REFRESH,
        /** Write {@code voided_at} from the notification alone, with no Google call. */
        REVOKE
    }

    private final int wire;
    private final RtdnAction action;

    SubscriptionNotificationType(int wire, RtdnAction action) {
        this.wire = wire;
        this.action = action;
    }

    public int wire() {
        return wire;
    }

    public RtdnAction action() {
        return action;
    }

    /**
     * The action for a wire value, including one nobody has read the documentation for.
     *
     * <p>An unrecognised type is {@link RtdnAction#REFRESH}, never ignored: re-reading the
     * authoritative state from Google is the only answer that cannot be wrong, and
     * {@code SubscriptionState.fromWire} still maps anything unmapped to {@code UNKNOWN}, which
     * DENIES — so even the default branch cannot accidentally entitle.
     */
    public static RtdnAction actionOf(Integer wire) {
        if (wire != null) {
            for (SubscriptionNotificationType type : values()) {
                if (type.wire == wire) {
                    return type.action;
                }
            }
        }
        return RtdnAction.REFRESH;
    }

    /** The named constant for a wire value, when there is one. */
    public static SubscriptionNotificationType of(Integer wire) {
        if (wire != null) {
            for (SubscriptionNotificationType type : values()) {
                if (type.wire == wire) {
                    return type;
                }
            }
        }
        return null;
    }
}
