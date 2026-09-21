package az.technest.whereis.marketplace;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.migration.Migrations;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every enum-ish column of the marketplace (V12, extended by V13) against its Java enum, byte for
 * byte, using the EFFECTIVE constraint across all migrations rather than a regex over one file.
 */
class ListingEnumsTest {

    @Test
    void statusConstantsMatchTheEffectiveCheckByteForByte() {
        List<String> allowed = Migrations.effectiveCheckValues("ck_listings_status", "status");

        assertThat(allowed).containsExactly("ACTIVE", "SOLD", "WITHDRAWN");
        assertThat(Arrays.stream(ListingStatus.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(allowed);
    }

    @Test
    void hiddenReasonConstantsMatchTheEffectiveCheckByteForByte() {
        List<String> allowed =
                Migrations.effectiveCheckValues("ck_listings_hidden_reason", "hidden_reason");

        assertThat(Arrays.stream(ListingHiddenReason.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(allowed);
    }

    /**
     * V13's seller-level sanction. A SECOND reason enum rather than more values on
     * {@link ListingHiddenReason}: that one answers "what is wrong with this listing", this one
     * answers "what is wrong with this seller", and one enum would have forced every future value
     * to make sense at both levels.
     */
    @Test
    void sellerBlockReasonConstantsMatchTheEffectiveCheckByteForByte() {
        List<String> allowed =
                Migrations.effectiveCheckValues("ck_blocked_sellers_reason", "reason");

        assertThat(Arrays.stream(SellerBlockReason.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(allowed);
    }

    @Test
    void currencyIsAOneElementListAndWideningItMeansWideningTheEnum() {
        List<String> allowed =
                Migrations.effectiveCheckValues("ck_listings_price_currency", "price_currency");

        assertThat(allowed).containsExactly("AZN");
        assertThat(Arrays.stream(ListingCurrency.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(allowed);
    }

    @Test
    void reportReasonConstantsMatchTheEffectiveCheckByteForByte() {
        List<String> allowed =
                Migrations.effectiveCheckValues("ck_listing_reports_reason", "reason");

        assertThat(Arrays.stream(ListingReportReason.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(allowed);
    }

    /**
     * The nullable one — {@code CHECK (review_outcome IS NULL OR review_outcome IN (...))}. This is
     * the shape the old single-file helper could not read at all, and the reason
     * {@code Migrations}'s ADD pattern now allows a prefix between {@code CHECK (} and the column.
     */
    @Test
    void reportOutcomeConstantsMatchTheEffectiveCheckByteForByte() {
        List<String> allowed =
                Migrations.effectiveCheckValues("ck_listing_reports_outcome", "review_outcome");

        assertThat(allowed).containsExactly("UPHELD", "DISMISSED");
        assertThat(Arrays.stream(ListingReportOutcome.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(allowed);
    }
}
