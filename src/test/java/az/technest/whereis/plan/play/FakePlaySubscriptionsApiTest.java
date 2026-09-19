package az.technest.whereis.plan.play;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.PlanCatalog;
import az.technest.whereis.plan.PlanCatalog.TierConfig;
import az.technest.whereis.plan.SubscriptionState;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The port fake itself. It is what every other offline test of the purchase flow runs against, so
 * its own grammar has to be pinned — a fake that quietly stops producing a CANCELED row would make
 * a whole class of tests pass for the wrong reason.
 */
class FakePlaySubscriptionsApiTest {

    private static PlanCatalog catalog() {
        Map<Plan, TierConfig> tiers = new EnumMap<>(Plan.class);
        tiers.put(Plan.FREE, new TierConfig(1, 100, null));
        tiers.put(Plan.STANDARD, new TierConfig(3, 300, "whereis_standard_annual"));
        tiers.put(Plan.PRO, new TierConfig(5, 600, "whereis_pro_annual"));
        tiers.put(Plan.MAX, new TierConfig(10, null, "whereis_max_annual"));
        tiers.put(Plan.UNLIMITED, new TierConfig(null, null, null));
        return new PlanCatalog(tiers);
    }

    private final FakePlaySubscriptionsApi play = new FakePlaySubscriptionsApi(catalog());

    @Test
    void anActivePurchaseCarriesItsTiersProductAndAYearOfEntitlement() {
        PlaySubscription purchase = play.get("fake-active-pro");

        assertThat(purchase.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(purchase.rawState()).isEqualTo("SUBSCRIPTION_STATE_ACTIVE");
        assertThat(purchase.lineItems()).singleElement()
                .satisfies(lineItem -> {
                    assertThat(lineItem.productId()).isEqualTo("whereis_pro_annual");
                    assertThat(lineItem.basePlanId()).isEqualTo("annual");
                    assertThat(lineItem.expiryTime()).isAfter(Instant.now());
                });
        assertThat(purchase.acknowledged()).isFalse();
    }

    @Test
    void canceledAndGracePeriodStillEntitleWhilePausedAndOnHoldDoNot() {
        assertThat(play.get("fake-canceled-standard").state().entitles()).isTrue();
        assertThat(play.get("fake-grace-standard").state().entitles()).isTrue();
        assertThat(play.get("fake-paused-standard").state().entitles()).isFalse();
        assertThat(play.get("fake-onhold-standard").state().entitles()).isFalse();
        assertThat(play.get("fake-expired-standard").state().entitles()).isFalse();
        // Paused and on-hold keep a FUTURE expiry on purpose: the state predicate has to be what
        // excludes them, not the clock.
        assertThat(play.get("fake-paused-standard").expiryTime()).isAfter(Instant.now());
    }

    @Test
    void aPendingPurchaseHasNoExpiryAtAll() {
        PlaySubscription pending = play.get("fake-pending-max");

        assertThat(pending.state()).isEqualTo(SubscriptionState.PENDING);
        assertThat(pending.expiryTime()).isNull();
    }

    @Test
    void aStateGoogleAddsAfterThisShipsBecomesUnknownAndDenies() {
        PlaySubscription weird = play.get("fake-weirdstate-pro");

        assertThat(weird.rawState()).isEqualTo("SUBSCRIPTION_STATE_SOMETHING_NEW");
        assertThat(weird.state()).isEqualTo(SubscriptionState.UNKNOWN);
        assertThat(weird.state().entitles()).isFalse();
    }

    @Test
    void aPromotionIsMarkedOnTheLineItemAndNowhereElse() {
        PlaySubscription trial = play.get("fake-trial-standard");

        // signupPromotion is a field of SubscriptionPurchaseLineItem in the real API, and it is the
        // ONLY marker of a promo-code redemption — during one, Google reports the FULL price.
        assertThat(trial.lineItems()).singleElement()
                .satisfies(lineItem -> assertThat(lineItem.signupPromotion()).isTrue());
        assertThat(trial.signupPromotion()).isTrue();
        assertThat(play.get("fake-active-standard").signupPromotion()).isFalse();
    }

    @Test
    void testPurchasesAcknowledgementAndLinkedTokensAreAllRepresentable() {
        assertThat(play.get("fake-test-pro").testPurchase()).isTrue();
        assertThat(play.get("fake-acked-pro").acknowledged()).isTrue();
        assertThat(play.get("fake-linked-pro").linkedPurchaseToken()).isEqualTo("fake-linked-pro-previous");
    }

    @Test
    void anAccountSuffixSetsTheObfuscatedAccountId() {
        String hash = "a".repeat(64);

        PlaySubscription purchase = play.get("fake-active-max@" + hash);

        assertThat(purchase.obfuscatedExternalAccountId()).isEqualTo(hash);
        assertThat(play.get("fake-active-max").obfuscatedExternalAccountId()).isNull();
    }

    @Test
    void aForeignProductIsReportedTruthfullyRatherThanRemapped() {
        assertThat(play.get("fake-foreignproduct-pro").lineItems()).singleElement()
                .satisfies(lineItem -> assertThat(lineItem.productId())
                        .isEqualTo("com.example.not_a_whereis_product"));
    }

    @Test
    void anOutageIsRetryableAndAnUnknownTokenIsNot() {
        assertThatThrownBy(() -> play.get("fake-outage-anything"))
                .isInstanceOf(PlayApiException.class);
        assertThatThrownBy(() -> play.get("some-token-google-never-issued"))
                .isInstanceOf(PlayPurchaseInvalidException.class);
        assertThatThrownBy(() -> play.get("fake-active-platinum"))
                .isInstanceOf(PlayPurchaseInvalidException.class);
        assertThatThrownBy(() -> play.get("fake-nonsense-pro"))
                .isInstanceOf(PlayPurchaseInvalidException.class);
    }

    @Test
    void acknowledgeIsIdempotentAndRecordedExceptWhenItIsMadeToFail() {
        play.acknowledge("whereis_pro_annual", "fake-active-pro");
        play.acknowledge("whereis_pro_annual", "fake-active-pro");

        assertThat(play.acknowledgedTokens()).containsExactly("fake-active-pro");

        assertThatThrownBy(() -> play.acknowledge("whereis_pro_annual", "fake-ackfails-pro"))
                .isInstanceOf(PlayApiException.class);
        assertThat(play.acknowledgedTokens()).doesNotContain("fake-ackfails-pro");
    }
}
