package az.technest.whereis.plan.play;

import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.PlanCatalog;
import az.technest.whereis.plan.SubscriptionState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A deterministic, rule-based {@link PlaySubscriptionsApi} driven entirely by the token STRING, so
 * integration tests exercise the real branches of the verification flow instead of mocking the
 * port. The same idea as {@code MockAiAssistant}, and the same reason: {@code ./gradlew build} and
 * the whole integration suite must run with no network, no Docker registry for Google and no Play
 * Console — which does not exist yet.
 *
 * <p><strong>It must never be reachable in production.</strong> Its tokens are guessable literals,
 * so a production process that selected it would hand the top tier to anyone who posted
 * {@code fake-active-max}. {@code PlayConfig} therefore refuses to construct it while the
 * {@code prod} profile is active — structurally unavailable, not merely unselected.
 *
 * <p>The grammar (everything after an optional {@code @<64 hex>} suffix, which sets
 * {@code obfuscatedExternalAccountId}):
 * <pre>
 *   fake-active-{standard|pro|max}   ACTIVE,          expiry now+365d, acknowledged=false
 *   fake-canceled-&lt;tier&gt;             CANCELED,        expiry now+30d   (STILL ENTITLES)
 *   fake-grace-&lt;tier&gt;                IN_GRACE_PERIOD, expiry now+3d    (entitles)
 *   fake-paused-&lt;tier&gt;               PAUSED,          expiry now+90d   (does NOT entitle)
 *   fake-onhold-&lt;tier&gt;               ON_HOLD,         expiry now+90d   (does NOT entitle)
 *   fake-expired-&lt;tier&gt;              EXPIRED,         expiry now-1d
 *   fake-pending-&lt;tier&gt;              PENDING,         NO expiry at all
 *   fake-weirdstate-&lt;tier&gt;           rawState "SUBSCRIPTION_STATE_SOMETHING_NEW" -&gt; UNKNOWN
 *   fake-trial-&lt;tier&gt;                ACTIVE + the LINE ITEM carries signupPromotion
 *   fake-test-&lt;tier&gt;                 ACTIVE + testPurchase
 *   fake-acked-&lt;tier&gt;                ACTIVE + acknowledged=true (acknowledge must be skipped)
 *   fake-linked-&lt;tier&gt;               ACTIVE + linkedPurchaseToken = "&lt;token&gt;-previous"
 *   fake-foreignproduct-&lt;tier&gt;       ACTIVE, but the line item names a product nothing maps
 *   fake-ackfails-&lt;tier&gt;             ACTIVE; acknowledge() throws PlayApiException
 *   fake-outage-…                    get() throws PlayApiException
 *   anything else                    get() throws PlayPurchaseInvalidException
 * </pre>
 *
 * <p>{@code fake-trial-*} deliberately carries a NON-zero price in spirit: the record has no price
 * at all, which is the point — a promotion is detected by {@code signupPromotion} and never by a
 * price, because during a promo trial Google reports the FULL amount.
 */
public class FakePlaySubscriptionsApi implements PlaySubscriptionsApi {

    private static final String BASE_PLAN_ID = "annual";
    private static final Duration ANNUAL = Duration.ofDays(365);

    private final PlanCatalog catalog;
    private final Set<String> acknowledged = ConcurrentHashMap.newKeySet();

    public FakePlaySubscriptionsApi(PlanCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public PlaySubscription get(String purchaseToken) {
        String token = purchaseToken == null ? "" : purchaseToken;
        String accountId = null;
        int at = token.indexOf('@');
        if (at >= 0) {
            accountId = token.substring(at + 1);
            token = token.substring(0, at);
        }
        if (token.startsWith("fake-outage")) {
            throw new PlayApiException("Fake Play API outage for the token ending " + tail(token));
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("fake-")) {
            throw new PlayPurchaseInvalidException("Google does not know this purchase token");
        }
        String[] parts = lower.split("-");
        if (parts.length < 3) {
            throw new PlayPurchaseInvalidException("Google does not know this purchase token");
        }
        String kind = parts[1];
        Plan tier = tierNamed(parts[2]);
        Instant now = Instant.now();

        SubscriptionState state = SubscriptionState.ACTIVE;
        String rawState = "SUBSCRIPTION_STATE_ACTIVE";
        Instant expiry = now.plus(ANNUAL);
        boolean alreadyAcknowledged = false;
        boolean testPurchase = false;
        boolean promotion = false;
        String linked = null;
        String productId = catalog.of(tier).productId();

        switch (kind) {
            case "active", "ackfails" -> { /* the defaults */ }
            case "canceled" -> {
                state = SubscriptionState.CANCELED;
                rawState = "SUBSCRIPTION_STATE_CANCELED";
                expiry = now.plus(Duration.ofDays(30));
            }
            case "grace" -> {
                state = SubscriptionState.IN_GRACE_PERIOD;
                rawState = "SUBSCRIPTION_STATE_IN_GRACE_PERIOD";
                expiry = now.plus(Duration.ofDays(3));
            }
            case "paused" -> {
                state = SubscriptionState.PAUSED;
                rawState = "SUBSCRIPTION_STATE_PAUSED";
                expiry = now.plus(Duration.ofDays(90));
            }
            case "onhold" -> {
                state = SubscriptionState.ON_HOLD;
                rawState = "SUBSCRIPTION_STATE_ON_HOLD";
                expiry = now.plus(Duration.ofDays(90));
            }
            case "expired" -> {
                state = SubscriptionState.EXPIRED;
                rawState = "SUBSCRIPTION_STATE_EXPIRED";
                expiry = now.minus(Duration.ofDays(1));
            }
            case "pending" -> {
                state = SubscriptionState.PENDING;
                rawState = "SUBSCRIPTION_STATE_PENDING";
                expiry = null;
            }
            case "weirdstate" -> {
                rawState = "SUBSCRIPTION_STATE_SOMETHING_NEW";
                state = SubscriptionState.fromWire(rawState);
            }
            case "trial" -> promotion = true;
            case "test" -> testPurchase = true;
            case "acked" -> alreadyAcknowledged = true;
            case "linked" -> linked = purchaseToken + "-previous";
            case "foreignproduct" -> productId = "com.example.not_a_whereis_product";
            default -> throw new PlayPurchaseInvalidException("Google does not know this purchase token");
        }

        PlayLineItem lineItem = new PlayLineItem(productId, BASE_PLAN_ID, expiry,
                state == SubscriptionState.ACTIVE, promotion);
        return new PlaySubscription(state, rawState, List.of(lineItem), now.minus(Duration.ofMinutes(1)),
                expiry, accountId, alreadyAcknowledged, testPurchase,
                "GPA.FAKE-" + Integer.toHexString(purchaseToken.hashCode()), linked);
    }

    @Override
    public void acknowledge(String productId, String purchaseToken) {
        if (purchaseToken != null && purchaseToken.toLowerCase(Locale.ROOT).startsWith("fake-ackfails")) {
            throw new PlayApiException("Fake acknowledge failure for the token ending " + tail(purchaseToken));
        }
        acknowledged.add(purchaseToken);
    }

    /** Assertion surface, and the only one: which tokens were acknowledged, in no particular order. */
    public Set<String> acknowledgedTokens() {
        return Set.copyOf(acknowledged);
    }

    /** Test-only reset, so one shared Spring context does not leak acknowledgements between ITs. */
    public void reset() {
        acknowledged.clear();
    }

    private Plan tierNamed(String name) {
        for (Plan tier : catalog.purchasable()) {
            if (tier.name().equalsIgnoreCase(name)) {
                return tier;
            }
        }
        throw new PlayPurchaseInvalidException("Google does not know this purchase token");
    }

    private static String tail(String token) {
        return token.length() <= 6 ? token : "…" + token.substring(token.length() - 6);
    }
}
