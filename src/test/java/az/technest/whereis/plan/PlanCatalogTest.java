package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.technest.whereis.plan.PlanCatalog.TierConfig;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** The ladder as configured, and every way a half-configured environment must fail at startup. */
class PlanCatalogTest {

    private static PlanCatalog catalog(Map<Plan, TierConfig> tiers) {
        return new PlanCatalog(tiers);
    }

    private static Map<Plan, TierConfig> shipped() {
        Map<Plan, TierConfig> tiers = new EnumMap<>(Plan.class);
        tiers.put(Plan.FREE, new TierConfig(1, 100, 1, null));
        tiers.put(Plan.STANDARD, new TierConfig(3, 300, 3, "whereis_standard_annual"));
        tiers.put(Plan.PRO, new TierConfig(5, 600, 10, "whereis_pro_annual"));
        tiers.put(Plan.MAX, new TierConfig(10, null, 25, "whereis_max_annual"));
        tiers.put(Plan.UNLIMITED, new TierConfig(null, null, null, null));
        return tiers;
    }

    // ---------------------------------------------------------------- the actually shipped config

    /**
     * Binds the REAL {@code application.yml}, because the one thing a hand-built fixture cannot
     * prove is that the file on the classpath says what this test thinks it says — specifically
     * that a blank {@code items:} binds to {@code null} rather than to 0 or a failure, which is the
     * entire mechanism behind "Max has unlimited items and ten spaces".
     */
    @Test
    void theShippedApplicationYmlBindsToTheFourTierLadder() throws java.io.IOException {
        PlanCatalog catalog = bindApplicationYml();

        assertThat(catalog.spaceLimit(Plan.FREE)).isEqualTo(1);
        assertThat(catalog.itemLimit(Plan.FREE)).isEqualTo(100);
        assertThat(catalog.spaceLimit(Plan.STANDARD)).isEqualTo(3);
        assertThat(catalog.itemLimit(Plan.STANDARD)).isEqualTo(300);
        assertThat(catalog.spaceLimit(Plan.PRO)).isEqualTo(5);
        assertThat(catalog.itemLimit(Plan.PRO)).isEqualTo(600);
        assertThat(catalog.spaceLimit(Plan.MAX)).isEqualTo(10);
        assertThat(catalog.itemLimit(Plan.MAX)).isNull();
        assertThat(catalog.spaceLimit(Plan.UNLIMITED)).isNull();
        assertThat(catalog.itemLimit(Plan.UNLIMITED)).isNull();

        assertThat(catalog.of(Plan.STANDARD).productId()).isEqualTo("whereis_standard_annual");
        assertThat(catalog.of(Plan.PRO).productId()).isEqualTo("whereis_pro_annual");
        assertThat(catalog.of(Plan.MAX).productId()).isEqualTo("whereis_max_annual");
        assertThat(catalog.of(Plan.FREE).productId()).isNull();
        assertThat(catalog.of(Plan.UNLIMITED).productId()).isNull();
    }

    private static PlanCatalog bindApplicationYml() throws java.io.IOException {
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("whereis", PlanCatalog.class)
                .orElseThrow(() -> new AssertionError("whereis.plans did not bind"));
    }

    // ------------------------------------------------------------------------------ the accessors

    @Test
    void theLadderIsFreeToMaxAndNeverOffersTheOperatorGrant() {
        PlanCatalog catalog = catalog(shipped());

        assertThat(catalog.ladder()).containsExactly(Plan.FREE, Plan.STANDARD, Plan.PRO, Plan.MAX);
        assertThat(catalog.purchasable()).containsExactly(Plan.STANDARD, Plan.PRO, Plan.MAX);
        // UNLIMITED is never listed: it is not purchasable, and a client that saw it would render
        // it as an option.
        assertThat(catalog.ladder()).doesNotContain(Plan.UNLIMITED);
    }

    @Test
    void anUnknownProductIdIsEmptyRatherThanGuessed() {
        PlanCatalog catalog = catalog(shipped());

        assertThat(catalog.tierOf("whereis_pro_annual")).contains(Plan.PRO);
        // Never FREE, never the lowest paid tier, never an exception the caller has to map.
        assertThat(catalog.tierOf("whereis_platinum_annual")).isEmpty();
        assertThat(catalog.tierOf("")).isEmpty();
        assertThat(catalog.tierOf(null)).isEmpty();
    }

    @Test
    void anUpgradeIsOnlyOfferedWhenAHigherTierActuallyRaisesThatAllowance() {
        PlanCatalog catalog = catalog(shipped());

        assertThat(catalog.aHigherTierRaisesSpaces(Plan.FREE)).isTrue();
        assertThat(catalog.aHigherTierRaisesItems(Plan.PRO)).isTrue();          // MAX is unlimited
        // At the top of the PAID ladder there is nothing to sell, on either allowance: MAX is ten
        // spaces and UNLIMITED cannot be bought. Advertising a purchase that does not exist is a
        // Play policy exposure as well as a lie.
        assertThat(catalog.aHigherTierRaisesSpaces(Plan.MAX)).isFalse();
        assertThat(catalog.aHigherTierRaisesItems(Plan.MAX)).isFalse();
        assertThat(catalog.aHigherTierRaisesSpaces(Plan.UNLIMITED)).isFalse();
        assertThat(catalog.aHigherTierRaisesItems(Plan.UNLIMITED)).isFalse();
    }

    // ------------------------------------------------------------------- startup failures, by name

    @Test
    void aMissingTierNamesTheKeyThatIsMissing() {
        Map<Plan, TierConfig> incomplete = shipped();
        incomplete.remove(Plan.PRO);

        assertThatThrownBy(() -> catalog(incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("whereis.plans.pro is not configured");
    }

    @Test
    void anEmptyPlansBlockStillNamesAKeyRatherThanFailingInsideTheJdk() {
        // new EnumMap<>(someEmptyNonEnumMap) throws "Specified map is empty" — a JDK message that
        // names nothing. The catalog builds its EnumMap defensively so this stays diagnosable.
        assertThatThrownBy(() -> catalog(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("whereis.plans.free is not configured");
        assertThatThrownBy(() -> catalog(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("whereis.plans.free is not configured");
    }

    @Test
    void aLadderThatGoesDownNamesBothTiersAndTheRule() {
        // THE RUNBOOK CASE: deploy/README.md Step 10 tells an operator to raise the free tier out
        // of reach before promoting a build. WHEREIS_PLANS_FREE_ITEMS=999999 alone is a ladder that
        // goes down, and the message has to say so well enough to act on at 2am — otherwise the
        // container crash-loops on a production VM while the operator follows the runbook.
        Map<Plan, TierConfig> inverted = shipped();
        inverted.put(Plan.FREE, new TierConfig(1, 999999, 1, null));

        assertThatThrownBy(() -> catalog(inverted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.plans.free.items (999999)")
                .hasMessageContaining("whereis.plans.standard.items (300)")
                .hasMessageContaining("a higher tier may never allow less")
                .hasMessageContaining("raise every tier above it too");

        // And raising every tier above it, as the message says, boots.
        Map<Plan, TierConfig> raised = shipped();
        raised.put(Plan.FREE, new TierConfig(1, 999999, 1, null));
        raised.put(Plan.STANDARD, new TierConfig(3, 999999, 3, "whereis_standard_annual"));
        raised.put(Plan.PRO, new TierConfig(5, 999999, 10, "whereis_pro_annual"));
        assertThat(catalog(raised).itemLimit(Plan.FREE)).isEqualTo(999999);
    }

    @Test
    void aFiniteCeilingAboveAnUnlimitedOneIsAlsoALadderThatGoesDown() {
        Map<Plan, TierConfig> inverted = shipped();
        inverted.put(Plan.PRO, new TierConfig(5, null, 10, "whereis_pro_annual"));
        inverted.put(Plan.MAX, new TierConfig(10, 600, 25, "whereis_max_annual"));

        assertThatThrownBy(() -> catalog(inverted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.plans.pro.items (no ceiling)")
                .hasMessageContaining("whereis.plans.max.items (600)");
    }

    @Test
    void aPurchasableTierWithoutAProductIdCannotBoot() {
        Map<Plan, TierConfig> tiers = shipped();
        tiers.put(Plan.PRO, new TierConfig(5, 600, 10, null));

        assertThatThrownBy(() -> catalog(tiers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.plans.pro.product-id must be set");
    }

    @Test
    void anUnpurchasableTierWithAProductIdCannotBoot() {
        Map<Plan, TierConfig> tiers = shipped();
        tiers.put(Plan.UNLIMITED, new TierConfig(null, null, null, "whereis_unlimited_annual"));

        assertThatThrownBy(() -> catalog(tiers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.plans.unlimited.product-id must NOT be set");
    }

    @Test
    void twoTiersMayNotShareAProductId() {
        Map<Plan, TierConfig> tiers = shipped();
        tiers.put(Plan.MAX, new TierConfig(10, null, 25, "whereis_pro_annual"));

        assertThatThrownBy(() -> catalog(tiers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is already used by whereis.plans.pro");
    }

    @Test
    void theOperatorGrantIsSuppliedRatherThanConfigured() {
        // A YAML entry whose every value is blank binds to nothing at all, so "unlimited with no
        // ceilings and no product" is not expressible in the file — and does not need to be.
        Map<Plan, TierConfig> withoutUnlimited = shipped();
        withoutUnlimited.remove(Plan.UNLIMITED);

        PlanCatalog catalog = catalog(withoutUnlimited);

        assertThat(catalog.spaceLimit(Plan.UNLIMITED)).isNull();
        assertThat(catalog.itemLimit(Plan.UNLIMITED)).isNull();
        assertThat(catalog.of(Plan.UNLIMITED).productId()).isNull();
    }

    @Test
    void theOperatorGrantMustBeUnlimitedOnEveryAllowance() {
        Map<Plan, TierConfig> tiers = shipped();
        tiers.put(Plan.UNLIMITED, new TierConfig(20, null, 20, null));

        assertThatThrownBy(() -> catalog(tiers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.plans.unlimited must leave spaces, items and listings");
    }

    @Test
    void aCeilingBelowOneIsATypoRatherThanAProductDecision() {
        Map<Plan, TierConfig> tiers = shipped();
        tiers.put(Plan.FREE, new TierConfig(0, 100, 0, null));

        assertThatThrownBy(() -> catalog(tiers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.plans.free.spaces must be at least 1 or blank");
    }

    @Test
    void theConfiguredMapIsNotWritableThroughTheAccessor() {
        PlanCatalog catalog = catalog(shipped());
        List<Plan> before = catalog.ladder();

        assertThatThrownBy(() -> catalog.plans().remove(Plan.PRO))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(catalog.ladder()).isEqualTo(before);
    }
}
