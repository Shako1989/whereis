package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Enum-vs-CHECK drift guard for {@code users.plan}, and the ladder's own order.
 *
 * <p>It reads the EFFECTIVE constraint across every migration rather than one file (see
 * {@link Migrations}), so V10's DROP + widened ADD is what it checks, a future V11 that widens it
 * again is checked too, and a V11 that adds a constant while leaving an older constraint in place
 * fails here instead of on the first {@code STANDARD} insert.
 */
class PlanTest {

    @Test
    void theLadderIsOrderedWeakestFirst() {
        // The declaration order IS the ladder: Plan.higherOf, isAtLeast and every max() in
        // PlanLimitEnforcer are Enum.compareTo over exactly this sequence. Inserting a constant in
        // the middle silently re-ranks everything, which is what this asserts against.
        assertThat(Plan.values()).containsExactly(
                Plan.FREE, Plan.STANDARD, Plan.PRO, Plan.MAX, Plan.UNLIMITED);
    }

    @Test
    void constantsMatchTheEffectiveCheckByteForByte() {
        List<String> allowed = Migrations.effectiveCheckValues("ck_users_plan", "plan");

        assertThat(allowed).containsExactly("FREE", "STANDARD", "PRO", "MAX", "UNLIMITED");
        assertThat(Arrays.stream(Plan.values()).map(Enum::name).toList())
                .containsExactlyElementsOf(allowed);
    }

    @Test
    void onlyThreeOfTheFiveTiersAreEverPurchasable() {
        // FREE is not a purchase and UNLIMITED is an operator grant. The database agrees — see
        // SubscriptionTierTest — and that agreement is what keeps "granted" distinguishable from
        // "paid" no matter what billing later writes.
        assertThat(Arrays.stream(Plan.values()).filter(Plan::isPurchasable).toList())
                .containsExactly(Plan.STANDARD, Plan.PRO, Plan.MAX);
    }

    @Test
    void higherOfIsTheWholeEntitlementRule() {
        assertThat(Plan.higherOf(Plan.PRO, Plan.STANDARD)).isEqualTo(Plan.PRO);
        assertThat(Plan.higherOf(Plan.FREE, Plan.MAX)).isEqualTo(Plan.MAX);
        assertThat(Plan.higherOf(Plan.UNLIMITED, Plan.MAX)).isEqualTo(Plan.UNLIMITED);
        assertThat(Plan.higherOf(Plan.PRO, Plan.PRO)).isEqualTo(Plan.PRO);
        assertThat(Plan.MAX.isAtLeast(Plan.PRO)).isTrue();
        assertThat(Plan.PRO.isAtLeast(Plan.MAX)).isFalse();
        assertThat(Plan.PRO.isAtLeast(Plan.PRO)).isTrue();
    }

    @Test
    void noMigrationEverWritesUsersPlan() {
        // V9's design note, extended to EVERY migration that touches the column: nothing is
        // grandfathered, and an UPDATE here would erase the hand-made grants that are the only way
        // a tester gets access. Granting stays an operator action (deploy/README.md Step 10).
        Pattern update = Pattern.compile("UPDATE\\s+users\\s+SET[^;]*\\bplan\\b",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        assertThat(update.matcher(Migrations.allStatements()).find())
                .as("an UPDATE of users.plan in any migration")
                .isFalse();
    }

    @Test
    void v10IsTheOnlyMigrationThatChangesTheAllowedPlans() {
        // Guards the pairing the spec calls deliberate: the enum and the CHECK move together, in
        // one migration, or this test tells you which file you forgot.
        List<String> touching = Migrations.all().stream()
                .filter(migration -> migration.sql().contains("ck_users_plan"))
                .map(Migrations.Migration::name)
                .toList();

        assertThat(touching).containsExactly("V9__user_plan.sql", "V10__subscriptions.sql");
    }
}
