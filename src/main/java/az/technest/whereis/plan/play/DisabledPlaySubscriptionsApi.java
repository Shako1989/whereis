package az.technest.whereis.plan.play;

import java.time.Instant;

/**
 * The {@link PlaySubscriptionsApi} of a deployment with no Play Billing configuration
 * ({@code whereis.play.provider=disabled}): <strong>every method refuses, none of them lies.</strong>
 *
 * <p>Something has to satisfy the injection point — six collaborators take this port by
 * constructor — so the choice is not "a bean or no bean" but "a bean that refuses or a bean that
 * invents". Returning a plausible-looking {@link PlaySubscription} would be the second, and it is
 * the one thing this class exists to make impossible: a fabricated {@code ACTIVE} answer would walk
 * straight into {@code SubscriptionWriter} and grant a tier nobody paid for, which is precisely why
 * {@code FakePlaySubscriptionsApi} is refused under the prod profile. A refusal cannot grant
 * anything, and that is what makes this mode safe to run in production.
 *
 * <p>In practice nothing reaches these methods: {@code PurchaseVerificationService} refuses first
 * so the answer is the same 501 for every request shape, the RTDN endpoint rejects every caller
 * before a notification is parsed, and the three scheduled jobs do not run. This is the backstop
 * for the caller nobody has written yet — a loud, named exception instead of a silent wrong answer.
 */
public class DisabledPlaySubscriptionsApi implements PlaySubscriptionsApi {

    @Override
    public PlaySubscription get(String purchaseToken) {
        throw refuse("purchases.subscriptionsv2.get");
    }

    @Override
    public void acknowledge(String productId, String purchaseToken) {
        throw refuse("purchases.subscriptions.acknowledge");
    }

    @Override
    public void cancel(String productId, String purchaseToken) {
        throw refuse("purchases.subscriptions.cancel");
    }

    @Override
    public PlayVoidedPage listVoidedPurchases(Instant startTime, Instant endTime, String pageToken) {
        throw refuse("purchases.voidedpurchases.list");
    }

    /** Names the call that was attempted and the property that would enable it; never a token. */
    private static PlayBillingNotConfiguredException refuse(String call) {
        return new PlayBillingNotConfiguredException("Play Billing is not configured in this deployment "
                + "(whereis.play.provider=" + PlayProperties.DISABLED + "), so " + call
                + " cannot be called. Set PLAY_PROVIDER=google and supply PLAY_SERVICE_ACCOUNT_JSON "
                + "once the Play service account exists.");
    }
}
