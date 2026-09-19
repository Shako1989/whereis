package az.technest.whereis.plan.rtdn;

/**
 * Which sibling field of the RTDN envelope was populated. Persisted on
 * {@code play_notifications.notification_kind} and pinned by {@code ck_play_notifications_kind}
 * ({@code PlayNotificationEnumsTest} parses V11).
 *
 * <p><strong>These are SIBLINGS, not variants.</strong> One {@code DeveloperNotification} carries
 * {@code subscriptionNotification} OR {@code voidedPurchaseNotification} OR
 * {@code oneTimeProductNotification} OR {@code testNotification}, at the same level, and a handler
 * that reads only the first ignores every refund while looking perfectly healthy. This column is
 * what makes that mistake visible in the database rather than invisible in a log.
 *
 * <p>{@link #UNKNOWN} is a real stored value with two uses: a sibling Google adds after this ships
 * (recorded truthfully, ignored, never dropped — the payload is what a corrected handler is re-run
 * against), and a message whose contents could not be decoded at all, where nothing is known about
 * which sibling was meant.
 */
public enum PlayNotificationKind {

    SUBSCRIPTION,
    VOIDED_PURCHASE,
    ONE_TIME_PRODUCT,
    TEST,
    UNKNOWN
}
