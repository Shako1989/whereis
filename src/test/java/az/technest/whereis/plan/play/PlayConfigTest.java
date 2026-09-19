package az.technest.whereis.plan.play;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.PlanCatalog;
import az.technest.whereis.plan.PlanCatalog.TierConfig;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Provider selection, and the one thing about it that is a security property rather than a
 * convenience.
 */
class PlayConfigTest {

    private final PlayConfig config = new PlayConfig();

    private static PlanCatalog catalog() {
        Map<Plan, TierConfig> tiers = new EnumMap<>(Plan.class);
        tiers.put(Plan.FREE, new TierConfig(1, 100, null));
        tiers.put(Plan.STANDARD, new TierConfig(3, 300, "whereis_standard_annual"));
        tiers.put(Plan.PRO, new TierConfig(5, 600, "whereis_pro_annual"));
        tiers.put(Plan.MAX, new TierConfig(10, null, "whereis_max_annual"));
        tiers.put(Plan.UNLIMITED, new TierConfig(null, null, null));
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
                .hasMessageContaining("supported: fake, google");
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
