package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The one message a user actually reads. It is shown verbatim by the client, so a paying Standard
 * subscriber must not be told they are on the free plan, and nobody must be offered a product that
 * does not exist.
 */
class PlanLimitReachedExceptionTest {

    @Test
    void theRefusalIsAlways409PlanLimitReached() {
        PlanLimitReachedException refusal = PlanLimitReachedException.spaces(1, Plan.FREE, true);

        assertThat(refusal.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refusal.code()).isEqualTo(ErrorCode.PLAN_LIMIT_REACHED);
    }

    @Test
    void everyTierIsNamedByItsOwnName() {
        assertThat(PlanLimitReachedException.spaces(1, Plan.FREE, true))
                .hasMessage("Free plan limit reached: 1 space. Upgrade your plan for more spaces.");
        assertThat(PlanLimitReachedException.spaces(3, Plan.STANDARD, true))
                .hasMessage("Standard plan limit reached: 3 spaces. Upgrade your plan for more spaces.");
        assertThat(PlanLimitReachedException.spaces(5, Plan.PRO, true))
                .hasMessage("Pro plan limit reached: 5 spaces. Upgrade your plan for more spaces.");
    }

    @Test
    void anUpgradeIsNeverOfferedWhenNoHigherTierSellsMoreOfThatAllowance() {
        // MAX is ten spaces and there is nothing above it to buy — UNLIMITED is an operator grant.
        // "Subscribe for unlimited spaces" here would advertise a product that does not exist at any
        // purchasable tier, which is a Play policy exposure as well as a lie.
        assertThat(PlanLimitReachedException.spaces(10, Plan.MAX, false))
                .hasMessage("Max plan limit reached: 10 spaces.");
        assertThat(PlanLimitReachedException.spaces(10, Plan.MAX, false).getMessage())
                .doesNotContainIgnoringCase("upgrade")
                .doesNotContainIgnoringCase("subscribe");
    }

    @Test
    void theItemMessageAlwaysOffersArchivingBecauseArchivingAlwaysWorks() {
        assertThat(PlanLimitReachedException.activeItems(100, Plan.FREE, true))
                .hasMessage("Free plan limit reached: 100 active items. "
                        + "Archive an item to free room, or upgrade your plan for more items.");
        // Archiving frees room on EVERY tier, so that half of the sentence is unconditional even
        // where there is nothing left to sell.
        assertThat(PlanLimitReachedException.activeItems(600, Plan.PRO, false))
                .hasMessage("Pro plan limit reached: 600 active items. Archive an item to free room.");
    }

    @Test
    void oneIsSingular() {
        assertThat(PlanLimitReachedException.spaces(1, Plan.FREE, true).getMessage()).contains("1 space.");
        assertThat(PlanLimitReachedException.activeItems(1, Plan.FREE, true).getMessage())
                .contains("1 active item.");
    }
}
