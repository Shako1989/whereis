package az.technest.whereis.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.marketplace.board.dto.MarketCitiesResponse;
import az.technest.whereis.marketplace.board.dto.MarketCityResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The catalogue: the picker's body, and the publish-time gate that keeps a foreign-key violation
 * from ever becoming the answer.
 */
@ExtendWith(MockitoExtension.class)
class MarketCityCatalogTest {

    @Mock
    private MarketCityRepository repository;

    @InjectMocks
    private MarketCityCatalog catalog;

    /**
     * All three names on every row, and the ORDER COMES FROM THE QUERY. Nothing here sorts: the
     * order is {@code sort_order}, pre-computed as data because Azerbaijani collates ə, ğ, ı, ö, ş
     * and ü outside the Latin order and a runtime sort would scatter Ağdam, Gədəbəy and Şəki.
     */
    @Test
    void thePickerServesEveryNameAndPreservesTheOrderTheColumnDefines() {
        when(repository.findAllByActiveTrueOrderBySortOrderAsc()).thenReturn(List.of(
                city("BAKU", "Bakı", "Baku", "Баку", 100),
                city("KHANKENDI", "Xankəndi", "Khankendi", "Ханкенди", 120),
                city("SHAKI", "Şəki", "Shaki", "Шеки", 180)));

        MarketCitiesResponse response = catalog.picker();

        assertThat(response.cities())
                .extracting(MarketCityResponse::code)
                .containsExactly("BAKU", "KHANKENDI", "SHAKI");
        assertThat(response.cities().getFirst())
                .isEqualTo(new MarketCityResponse("BAKU", "Bakı", "Baku", "Баку", 100));
        // Xankəndi sits between Bakı and Şəki, which no Latin sort would produce — the whole reason
        // the order is data.
        assertThat(response.cities().get(1).nameAz()).isEqualTo("Xankəndi");
    }

    @Test
    void aKnownActiveCodeComesBackCanonicalised() {
        when(repository.existsByCodeAndActiveTrue("BAKU")).thenReturn(true);

        assertThat(catalog.requireSelectable("baku")).isEqualTo("BAKU");
        assertThat(catalog.requireSelectable("  BAKU ")).isEqualTo("BAKU");
    }

    /**
     * <strong>A 400 the client can act on, not the generic 409 a raw constraint violation
     * produces.</strong> Without this check the code reaches the INSERT, {@code fk_listings_city}
     * raises a 23503, and the handler answers 409 {@code CONFLICT} — no advice, not branchable, and
     * only after the public photo copy has been written.
     */
    @Test
    void anUnknownCodeIsA400ThatTellsTheClientToReReadTheList() {
        when(repository.existsByCodeAndActiveTrue("NOWHERE")).thenReturn(false);

        assertThatThrownBy(() -> catalog.requireSelectable("NOWHERE"))
                .isInstanceOf(UnknownMarketCityException.class)
                .hasMessageContaining("/api/v1/market/cities")
                .extracting(e -> ((UnknownMarketCityException) e).code())
                .isEqualTo(ErrorCode.MARKET_CITY_UNKNOWN);
    }

    /**
     * <strong>RETIREMENT, which is the whole reason this is a table.</strong> {@code active = false}
     * takes a place out of the picker AND out of what a new listing may name, while every listing
     * that already named it keeps its value. An enum could not have expressed this: removing the
     * constant would break the existing rows and keeping it would keep offering the place.
     */
    @Test
    void aRetiredCodeIsRefusedForANewListingWithTheSameAnswerAsAnUnknownOne() {
        when(repository.existsByCodeAndActiveTrue("NAFTALAN")).thenReturn(false);

        assertThatThrownBy(() -> catalog.requireSelectable("NAFTALAN"))
                .isInstanceOf(UnknownMarketCityException.class);
    }

    /**
     * A value that cannot be a code costs no statement — and the refusal is the SAME one, because
     * the client's next action is identical and telling the two apart would describe the catalogue
     * to anybody who asks.
     */
    @Test
    void aValueThatCannotBeACodeIsRefusedWithoutAskingTheDatabase() {
        assertThatThrownBy(() -> catalog.requireSelectable("28 May metro"))
                .isInstanceOf(UnknownMarketCityException.class);
        assertThatThrownBy(() -> catalog.requireSelectable("şəhər mərkəzi"))
                .isInstanceOf(UnknownMarketCityException.class);
        assertThatThrownBy(() -> catalog.requireSelectable(null))
                .isInstanceOf(UnknownMarketCityException.class);

        verifyNoInteractions(repository);
    }

    /**
     * The free text V15 replaced, refused by the TABLE rather than by the shape check — because
     * {@code "Bakı"} upper-cases to {@code "BAKI"} under {@code Locale.ROOT}, which is ASCII and so
     * looks exactly like a code. One 400 either way, which is the point: the seller sees one
     * behaviour, and the catalogue is the only thing that decides what exists.
     */
    @Test
    void theFreeTextTheCodesReplacedIsRefusedByTheTable() {
        when(repository.existsByCodeAndActiveTrue("BAKI")).thenReturn(false);

        assertThatThrownBy(() -> catalog.requireSelectable("Bakı"))
                .isInstanceOf(UnknownMarketCityException.class);
    }

    private static MarketCity city(String code, String az, String en, String ru, int order) {
        return MarketCity.builder()
                .code(code)
                .nameAz(az)
                .nameEn(en)
                .nameRu(ru)
                .sortOrder(order)
                .active(true)
                .build();
    }
}
