package az.technest.whereis.plan.play;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.PlanCatalog;
import az.technest.whereis.plan.PlanCatalog.TierConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

/**
 * Provider selection, and the one thing about it that is a security property rather than a
 * convenience.
 */
class PlayConfigTest {

    private final PlayConfig config = new PlayConfig();

    private static PlanCatalog catalog() {
        Map<Plan, TierConfig> tiers = new EnumMap<>(Plan.class);
        tiers.put(Plan.FREE, new TierConfig(1, 100, 1, null));
        tiers.put(Plan.STANDARD, new TierConfig(3, 300, 3, "whereis_standard_annual"));
        tiers.put(Plan.PRO, new TierConfig(5, 600, 10, "whereis_pro_annual"));
        tiers.put(Plan.MAX, new TierConfig(10, null, 25, "whereis_max_annual"));
        tiers.put(Plan.UNLIMITED, new TierConfig(null, null, null, null));
        return new PlanCatalog(tiers);
    }

    private static PlayProperties properties(String provider) {
        return new PlayProperties(provider, "az.technest.whereis", null, Duration.ofSeconds(10));
    }

    @Test
    void theFakeIsTheDefaultSoEveryTestRunsWithNoNetworkAndNoCredentials() {
        assertThat(properties(null).provider()).isEqualTo(PlayProperties.FAKE);
        assertThat(properties("  ").provider()).isEqualTo(PlayProperties.FAKE);
        assertThat(config.playSubscriptionsApi(properties("fake"), catalog(), new MockEnvironment()))
                .isInstanceOf(FakePlaySubscriptionsApi.class);
    }

    @Test
    void theFakeIsRefusedUnderTheProdProfileEvenWhenItIsExplicitlySelected() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod", "json");

        // Structurally unavailable, not merely unselected. Its tokens are guessable literals, so a
        // prod process that had it would hand the top tier to anyone who posted `fake-active-max` —
        // a self-service entitlement escalation gated on one environment variable's VALUE, which is
        // exactly what gets set while fixing a boot failure at 2am.
        assertThatThrownBy(() -> config.playSubscriptionsApi(properties("fake"), catalog(), prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.play.provider=fake is refused under the prod profile")
                .hasMessageContaining("PLAY_PROVIDER=google");
    }

    @Test
    void theGoogleProviderRefusesToStartWithoutItsServiceAccountKey() {
        assertThatThrownBy(() -> config.playSubscriptionsApi(properties("google"), catalog(),
                new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.play.service-account-json (PLAY_SERVICE_ACCOUNT_JSON)")
                .hasMessageContaining("whereis.play.provider=google");
    }

    @Test
    void anUnknownProviderNamesTheOnesThatExist() {
        assertThatThrownBy(() -> config.playSubscriptionsApi(properties("apple"), catalog(),
                new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown whereis.play.provider 'apple'")
                .hasMessageContaining("supported: fake, google, disabled");
    }

    /**
     * <strong>The third value, and the one production runs today.</strong> Permitted under the
     * prod profile — unlike {@code fake} — because the two are not variations on the same risk: the
     * fake GRANTS a tier to anyone who guesses a literal, while this one grants nothing to anyone
     * under any input. It is also the mode that needs no Google credentials at all, which is what
     * lets the free tier, the plan endpoint and the 409 wall serve production before a Play service
     * account exists.
     */
    @Test
    void theDisabledProviderIsPermittedUnderTheProdProfileAndNeedsNoCredentials() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod", "json");

        assertThat(config.playSubscriptionsApi(properties("disabled"), catalog(), prod))
                .isInstanceOf(DisabledPlaySubscriptionsApi.class);
        assertThat(properties("  DISABLED ").provider()).isEqualTo(PlayProperties.DISABLED);
        assertThat(properties("disabled").billingConfigured()).isFalse();
        assertThat(properties("google").billingConfigured()).isTrue();
        assertThat(properties("fake").billingConfigured()).isTrue();
    }

    /**
     * Every method refuses, and refuses with the SAME named exception. The alternative a reviewer
     * will reach for — returning a plausible empty/inactive {@link PlaySubscription} — is what this
     * asserts against: a fabricated Google answer walks into {@code SubscriptionWriter} and grants
     * a tier nobody paid for, which is the exact reason the fake is banned from production.
     */
    @Test
    void everyCallOnTheDisabledPortRefusesRatherThanInventingAnAnswer() {
        PlaySubscriptionsApi off = new DisabledPlaySubscriptionsApi();

        List<ThrowingCallable> everyCall = List.of(
                () -> off.get("any-token"),
                () -> off.acknowledge("whereis_pro_annual", "any-token"),
                () -> off.cancel("whereis_pro_annual", "any-token"),
                () -> off.listVoidedPurchases(Instant.EPOCH, Instant.now(), null));

        for (ThrowingCallable call : everyCall) {
            assertThatThrownBy(call)
                    .isInstanceOf(PlayBillingNotConfiguredException.class)
                    .satisfies(thrown -> {
                        assertThat(((PlayBillingNotConfiguredException) thrown).status())
                                .isEqualTo(HttpStatus.NOT_IMPLEMENTED);
                        assertThat(((PlayBillingNotConfiguredException) thrown).code())
                                .isEqualTo(ErrorCode.PLAY_BILLING_NOT_CONFIGURED);
                    })
                    .hasMessageContaining("whereis.play.provider=disabled");
        }
    }

    /** A refusal must never carry the token it was asked about — tokens are bearer credentials. */
    @Test
    void theRefusalNamesTheCallAndTheFixButNeverTheToken() {
        assertThatThrownBy(() -> new DisabledPlaySubscriptionsApi().get("super-secret-token"))
                .hasMessageContaining("purchases.subscriptionsv2.get")
                .hasMessageContaining("PLAY_PROVIDER=google")
                .hasMessageNotContaining("super-secret-token");
    }

    @Test
    void thePackageNameIsServerConfigWithASafeDefault() {
        // Never a request field: a caller must not be able to make this server ask Google about a
        // different app's purchase.
        assertThat(properties("fake").packageName()).isEqualTo("az.technest.whereis");
        assertThat(new PlayProperties("fake", "  ", null, null).packageName())
                .isEqualTo("az.technest.whereis");
        assertThat(new PlayProperties("fake", null, null, null).timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(new PlayProperties("fake", null, "   ", null).serviceAccountJson()).isNull();
    }
}
