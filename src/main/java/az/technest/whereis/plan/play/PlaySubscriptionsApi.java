package az.technest.whereis.plan.play;

/**
 * The Play Developer API, as this application needs it. Shaped exactly like {@code AiAssistant}: a
 * provider-agnostic port, no business logic, no data access, all output untrusted, and a
 * deterministic fake selected by configuration so {@code ./gradlew build} and the whole integration
 * suite run with no network, no Google credentials and no Play Console.
 *
 * <p>No implementation may be called from inside a database transaction — provider latency must
 * never hold a connection or a lock. {@code OwnershipScopingArchTest} makes that a build failure.
 */
public interface PlaySubscriptionsApi {

    /**
     * {@code purchases.subscriptionsv2.get(packageName, token)}.
     * {@code purchases.subscriptions.get} is DEPRECATED and must not be used. {@code packageName}
     * comes from configuration, never from the caller, so a client cannot make this server ask
     * Google about a different app.
     *
     * @throws PlayPurchaseInvalidException Google does not know this token (404, or not a purchase
     *                                      of this package). The client must DROP the token.
     * @throws PlayApiException             transport failure, 5xx, timeout or an auth failure.
     *                                      Retryable; nothing has been written.
     */
    PlaySubscription get(String purchaseToken);

    /**
     * {@code purchases.subscriptions.acknowledge(packageName, subscriptionId, token)}. There is NO
     * {@code subscriptionsv2} acknowledge method — this is the v1 endpoint and it needs the product
     * id, which is why the product id is a parameter here and not on {@link #get}.
     *
     * <p>Must happen within 3 days (5 MINUTES for a test purchase, which is every purchase on a
     * closed track) or Google auto-refunds and revokes. Idempotent on Google's side: acknowledging
     * an already-acknowledged purchase is not an error.
     *
     * @throws PlayApiException             retryable failure
     * @throws PlayPurchaseInvalidException Google does not know this token
     */
    void acknowledge(String productId, String purchaseToken);

    /**
     * {@code purchases.subscriptions.cancel(packageName, subscriptionId, token)}: turn auto-renew
     * OFF and let the already-paid term run out. The v1 endpoint, like {@link #acknowledge}, which
     * is why it too needs the product id.
     *
     * <p>Called only by {@code PlayCancellationJanitor}, draining the outbox
     * {@code AccountDeletionService} fills: Google Play does NOT cancel a subscription when a user
     * deletes their app account, so without this the person keeps being billed for a product they
     * can no longer sign in to.
     *
     * <p>Idempotent on Google's side; cancelling an already-cancelled subscription is not an error.
     * {@code subscriptionsv2.cancel} and {@code subscriptionsv2.revoke} also exist in the pinned
     * revision — revoking would refund the user and end access at once, which is a commercial
     * decision this application does not make on their behalf (see V11).
     *
     * @throws PlayPurchaseUnknownException Google answered 404 — nothing left to cancel
     * @throws PlayPurchaseInvalidException Google answered 400 — possibly OUR product id
     * @throws PlayApiException             retryable failure
     */
    void cancel(String productId, String purchaseToken);

    /**
     * {@code purchases.voidedpurchases.list}: the refunds and chargebacks in a time window — the
     * BACKSTOP for a {@code voidedPurchaseNotification} that was lost, given up on, or arrived
     * before the purchase was known to us.
     *
     * <p><strong>The implementation MUST set {@code type=1}.</strong> The endpoint defaults to
     * {@code type=0}, which is one-time products only: omit it and this returns an empty list
     * forever, every run succeeds, every metric is green, and no refund is ever caught. Note the
     * inversion with {@code voidedPurchaseNotification.productType}, where 1 = subscription and
     * 2 = one-time — two Google fields, the same numbers, opposite meanings.
     *
     * @param startTime inclusive start of the window (a fixed look-back, not a stored watermark)
     * @param endTime   exclusive end of the window
     * @param pageToken {@code tokenPagination.nextPageToken} from the previous page, or null first
     * @throws PlayApiException any failure; the sweep logs and stops, and runs again in six hours
     */
    PlayVoidedPage listVoidedPurchases(java.time.Instant startTime, java.time.Instant endTime,
                                       String pageToken);
}
