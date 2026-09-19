package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code ck_user_subscriptions_provenance} vs {@link PurchaseProvenance}. */
class PurchaseProvenanceTest {

    @Test
    void constantsMatchTheEffectiveCheckByteForByte() {
        List<String> allowed =
                Migrations.effectiveCheckValues("ck_user_subscriptions_provenance", "provenance");

        assertThat(allowed).containsExactly("PLAY_PURCHASE", "PROMO_CODE", "OPERATOR");
        assertThat(Arrays.stream(PurchaseProvenance.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(allowed);
    }

    @Test
    void onlyAnOperatorRowMayOmitTheTokenAndTheProduct() {
        // The two CHECKs that make a hand-written, time-boxed grant possible without fabricating a
        // purchase token in Google's global unique namespace — a fabricated token that collided
        // with a real one would make a paid purchase permanently unredeemable.
        String sql = Migrations.allStatements();

        assertThat(sql).contains("CHECK (purchase_token IS NOT NULL OR provenance = 'OPERATOR')");
        assertThat(sql).contains("CHECK (product_id IS NOT NULL OR provenance = 'OPERATOR')");
    }
}
