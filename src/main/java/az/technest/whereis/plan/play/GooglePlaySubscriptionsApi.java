package az.technest.whereis.plan.play;

import az.technest.whereis.plan.SubscriptionState;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.model.ExternalAccountIdentifiers;
import com.google.api.services.androidpublisher.model.OfferDetails;
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseLineItem;
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseV2;
import com.google.api.services.androidpublisher.model.SubscriptionPurchasesAcknowledgeRequest;
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

    /**
     * 404 and 400 mean Google does not recognise the token — no retry can help, so the client must
     * drop it. Everything else (401/403 auth failures included, because a rotated key is fixed on
     * the server and the purchase is still valid) is retryable.
     */
    private RuntimeException translate(GoogleJsonResponseException e, String operation) {
        int status = e.getStatusCode();
        if (status == 404 || status == 400) {
            return new PlayPurchaseInvalidException(
                    "Google does not know this purchase token (" + operation + ", HTTP " + status + ")", e);
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
                item.getSignupPromotion() != null);
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
