package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.migration.Migrations;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code ck_user_subscriptions_tier} vs {@link Plan#isPurchasable()} — the constraint that makes
 * "granted" and "paid" permanently distinguishable.
 */
class SubscriptionTierTest {

    @Test
    void onlyPurchasableTiersMayBeStoredOnASubscription() {
        List<String> allowed = Migrations.effectiveCheckValues("ck_user_subscriptions_tier", "tier");

        assertThat(allowed).containsExactly("STANDARD", "PRO", "MAX");
        assertThat(Arrays.stream(Plan.values()).filter(Plan::isPurchasable).map(Enum::name).toList())
                .containsExactlyElementsOf(allowed);
    }

    @Test
    void unlimitedIsUnrepresentableOnASubscriptionAndThatIsThePoint() {
        List<String> allowed = Migrations.effectiveCheckValues("ck_user_subscriptions_tier", "tier");

        // UNLIMITED is the operator grant. If billing could ever write it here, "this account was
        // granted access by hand" and "this account pays" would become the same row, and the next
        // expiry would erase the grant — the exact failure V9 was designed around.
        assertThat(allowed).doesNotContain(Plan.UNLIMITED.name());
        assertThat(allowed).doesNotContain(Plan.FREE.name());
    }
}
