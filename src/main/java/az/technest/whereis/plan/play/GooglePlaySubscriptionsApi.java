package az.technest.whereis.plan.play;

import az.technest.whereis.plan.SubscriptionState;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.model.ExternalAccountIdentifiers;
import com.google.api.services.androidpublisher.model.OfferDetails;
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseLineItem;
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseV2;
import com.google.api.services.androidpublisher.model.SubscriptionPurchasesAcknowledgeRequest;
import com.google.api.services.androidpublisher.model.TokenPagination;
import com.google.api.services.androidpublisher.model.VoidedPurchase;
import com.google.api.services.androidpublisher.model.VoidedPurchasesListResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The real Play Developer API. The ONLY class in the application that touches Google's generated
 * client, exactly as {@code MinioAdapter} is the only class that touches the MinIO SDK: no Google
 * type escapes past the records in this package, so a version bump or a change of client is a
 * change to one file.
 *
 * <p>Never exercised by {@code ./gradlew build} or {@code integrationTest} — the deterministic
 * {@link FakePlaySubscriptionsApi} is the default provider and the one the suites run. Its first
 * real exercise is the first purchase on a closed track, which is why the mapping below is written
 * against the generated client's ACTUAL shape rather than the documentation's prose:
 * {@code signupPromotion} and {@code latestSuccessfulOrderId} are fields of
 * {@link SubscriptionPurchaseLineItem}, not of {@link SubscriptionPurchaseV2}, and
 * {@code basePlanId} lives one level further down on {@link OfferDetails}.
 */
public class GooglePlaySubscriptionsApi implements PlaySubscriptionsApi {

    private static final String ACKNOWLEDGED = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED";

    private final AndroidPublisher publisher;
    private final PlayProperties properties;

    public GooglePlaySubscriptionsApi(AndroidPublisher publisher, PlayProperties properties) {
        this.publisher = publisher;
        this.properties = properties;
    }

    @Override
    public PlaySubscription get(String purchaseToken) {
        try {
            SubscriptionPurchaseV2 purchase = publisher.purchases().subscriptionsv2()
                    .get(properties.packageName(), purchaseToken)
                    .execute();
            return map(purchase);
        } catch (GoogleJsonResponseException e) {
            throw translate(e, "read");
        } catch (IOException e) {
            // Never include the token: it is a bearer credential (§6).
            throw new PlayApiException("Could not reach the Play Developer API", e);
        }
    }

    @Override
    public void acknowledge(String productId, String purchaseToken) {
        try {
            publisher.purchases().subscriptions()
                    .acknowledge(properties.packageName(), productId, purchaseToken,
                            new SubscriptionPurchasesAcknowledgeRequest())
                    .execute();
        } catch (GoogleJsonResponseException e) {
            throw translate(e, "acknowledge");
        } catch (IOException e) {
            throw new PlayApiException("Could not reach the Play Developer API to acknowledge a purchase", e);
        }
    }

    @Override
    public void cancel(String productId, String purchaseToken) {
        try {
            publisher.purchases().subscriptions()
                    .cancel(properties.packageName(), productId, purchaseToken)
                    .execute();
        } catch (GoogleJsonResponseException e) {
            throw translate(e, "cancel");
        } catch (IOException e) {
            throw new PlayApiException("Could not reach the Play Developer API to cancel a subscription", e);
        }
    }

    @Override
    public PlayVoidedPage listVoidedPurchases(Instant startTime, Instant endTime, String pageToken) {
        try {
            VoidedPurchasesListResponse response = voidedPurchasesRequest(startTime, endTime, pageToken)
                    .execute();
            List<PlayVoidedPurchase> purchases =
                    Optional.ofNullable(response.getVoidedPurchases()).orElse(List.of()).stream()
                            .filter(Objects::nonNull)
                            .map(GooglePlaySubscriptionsApi::mapVoided)
                            .toList();
            TokenPagination pagination = response.getTokenPagination();
            return new PlayVoidedPage(purchases, pagination == null ? null : pagination.getNextPageToken());
        } catch (GoogleJsonResponseException e) {
            // Deliberately NOT translate(): a 400/404 here says nothing about any one purchase, and
            // turning it into "the client must drop the token" would be nonsense. The sweep is a
            // backstop; failing it is always retryable, and the next run is six hours away.
            throw new PlayApiException("The Play Developer API answered HTTP " + e.getStatusCode()
                    + " on the voided-purchases sweep", e);
        } catch (IOException e) {
            throw new PlayApiException("Could not reach the Play Developer API to list voided purchases", e);
        }
    }

    /**
     * The built request, separated from {@code execute()} so it can be asserted OFFLINE. There is
     * no network in {@code ./gradlew build} and no Play Console anywhere, so the only way to prove
     * the one line below that matters is to build the request object and read it back.
     *
     * <p><strong>{@code setType(1)} is the single most dangerous line in this wave.</strong>
     * {@code purchases.voidedpurchases.list} defaults to {@code type=0}, which is ONE-TIME PRODUCTS
     * ONLY. Omit it and this sweep returns an empty list forever: every run succeeds, every metric
     * is green, and no refund is ever caught. Because that failure is invisible,
     * {@code PlayVoidedSweepTest} builds this request and asserts {@code getType() == 1}.
     *
     * <p>Note the inversion with {@code voidedPurchaseNotification.productType}, where 1 = SUBSCRIPTION
     * and 2 = one-time. Here 0 = one-time only and 1 = one-time AND subscriptions. Two Google
     * fields, the same numbers, opposite meanings; changing one to match the other by memory
     * silently breaks the other.
     *
     * <p>{@code setIncludeQuantityBasedPartialRefund(false)} is explicit for the same reason —
     * partial refunds are one-time-product-only, and saying so beats relying on a default.
     */
    AndroidPublisher.Purchases.Voidedpurchases.List voidedPurchasesRequest(
            Instant startTime, Instant endTime, String pageToken) throws IOException {
        return publisher.purchases().voidedpurchases().list(properties.packageName())
                .setType(1)
                .setStartTime(startTime.toEpochMilli())
                .setEndTime(endTime.toEpochMilli())
                .setMaxResults(1000L)
                .setIncludeQuantityBasedPartialRefund(false)
                .setToken(pageToken);
    }

    private static PlayVoidedPurchase mapVoided(VoidedPurchase voided) {
        Long voidedMillis = voided.getVoidedTimeMillis();
        return new PlayVoidedPurchase(
                voided.getPurchaseToken(),
                voided.getOrderId(),
                voidedMillis == null ? null : Instant.ofEpochMilli(voidedMillis),
                voided.getVoidedReason(),
                voided.getVoidedSource());
    }

    /**
     * 404 and 400 both mean the client must drop the token, and both still map to
     * {@link PlayPurchaseInvalidException} on the wire. They are nonetheless DIFFERENT exceptions
     * now, because two callers must tell them apart:
     * <ul>
     *   <li><strong>404</strong> — {@link PlayPurchaseUnknownException}. Google definitively does
     *       not have this purchase. Nothing left to cancel, nothing to refresh, no retry can
     *       help.</li>
     *   <li><strong>400</strong> — a bad request, which can be OUR bug rather than Google's answer
     *       about the purchase: {@code purchases.subscriptions.cancel} takes our stored product id,
     *       which may legitimately disagree with the token's current product. Treated as
     *       retryable-with-a-ceiling by every caller, and never as a reason to revoke.</li>
     * </ul>
     * Everything else (401/403 auth failures included, because a rotated key is fixed on the server
     * and the purchase is still valid) is retryable.
     */
    private RuntimeException translate(GoogleJsonResponseException e, String operation) {
        int status = e.getStatusCode();
        if (status == 404) {
            return new PlayPurchaseUnknownException(
                    "Google does not know this purchase token (" + operation + ", HTTP 404)", e);
        }
        if (status == 400) {
            return new PlayPurchaseInvalidException(
                    "Google refused the request as malformed (" + operation + ", HTTP 400)", e);
        }
        return new PlayApiException("The Play Developer API answered HTTP " + status
                + " on " + operation, e);
    }

    private PlaySubscription map(SubscriptionPurchaseV2 purchase) {
        List<PlayLineItem> lineItems = Optional.ofNullable(purchase.getLineItems()).orElse(List.of()).stream()
                .filter(Objects::nonNull)
                .map(GooglePlaySubscriptionsApi::mapLineItem)
                .toList();
        // The latest line-item expiry. Deliberately NOT what the verify endpoint stores — with more
        // than one line item, the latest over-entitles, so it stores the MATCHED item's own expiry.
        Instant expiry = lineItems.stream()
                .map(PlayLineItem::expiryTime)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        ExternalAccountIdentifiers identifiers = purchase.getExternalAccountIdentifiers();
        String rawState = purchase.getSubscriptionState();
        return new PlaySubscription(
                // Google sends "SUBSCRIPTION_STATE_ACTIVE"; comparing that to "ACTIVE" compiles and
                // matches nothing, so the mapping happens exactly here and nowhere else.
                SubscriptionState.fromWire(rawState),
                rawState,
                lineItems,
                instant(purchase.getStartTime()),
                expiry,
                identifiers == null ? null : identifiers.getObfuscatedExternalAccountId(),
                ACKNOWLEDGED.equals(purchase.getAcknowledgementState()),
                purchase.getTestPurchase() != null,
                // There is no top-level order id on SubscriptionPurchaseV2; it lives on the line item.
                Optional.ofNullable(purchase.getLineItems()).orElse(List.of()).stream()
                        .filter(Objects::nonNull)
                        .map(SubscriptionPurchaseLineItem::getLatestSuccessfulOrderId)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null),
                purchase.getLinkedPurchaseToken());
    }

    private static PlayLineItem mapLineItem(SubscriptionPurchaseLineItem item) {
        OfferDetails offer = item.getOfferDetails();
        boolean autoRenewing = item.getAutoRenewingPlan() != null
                && Boolean.TRUE.equals(item.getAutoRenewingPlan().getAutoRenewEnabled());
        return new PlayLineItem(
                item.getProductId(),
                offer == null ? null : offer.getBasePlanId(),
                instant(item.getExpiryTime()),
                autoRenewing,
                // Presence IS the marker. Never infer a promotion from a price: during a promo
                // trial autoRenewingPlan.recurringPrice reports the FULL amount.
                item.getSignupPromotion() != null,
                // The product this line item becomes at term end — a DEFERRED downgrade. Null-safe
                // twice over: the object is absent on every ordinary purchase.
                item.getDeferredItemReplacement() == null
                        ? null : item.getDeferredItemReplacement().getProductId());
    }

    /**
     * RFC 3339 to {@link Instant}. {@code OffsetDateTime.parse} rather than {@code Instant.parse}
     * because Google may send a numeric offset rather than 'Z'. An unparseable value becomes null
     * rather than an exception: a timestamp this server cannot read must not be able to refuse a
     * purchase the user paid for — the entitlement then falls back to the fail-closed
     * {@code entitled_until} the writer supplies.
     */
    private static Instant instant(String rfc3339) {
        if (rfc3339 == null || rfc3339.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(rfc3339).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
