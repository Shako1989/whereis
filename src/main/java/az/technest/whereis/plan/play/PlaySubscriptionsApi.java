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
}
