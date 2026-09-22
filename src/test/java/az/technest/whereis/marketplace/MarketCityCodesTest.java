package az.technest.whereis.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The one entry point that turns a client string into a city code, and the Java hazard these
 * particular names carry.
 *
 * <p>What is asserted here is everything the database cannot say: that a code is a stable ASCII
 * identifier rather than a label, that a value which cannot be one is rejected without a statement,
 * and that no case mapping anywhere on this path uses the default locale.
 */
class MarketCityCodesTest {

    @Test
    void aCodeResolvesWhateverTheCaseOrSurroundingSpace() {
        assertThat(MarketCityCodes.canonical("BAKU")).contains("BAKU");
        assertThat(MarketCityCodes.canonical("baku")).contains("BAKU");
        assertThat(MarketCityCodes.canonical("  Baku  ")).contains("BAKU");
        assertThat(MarketCityCodes.canonical("mingachevir")).contains("MINGACHEVIR");
    }

    /**
     * <strong>This is a SHAPE check and not a membership check</strong>, and the difference is the
     * design: membership is the database's answer (the catalogue for publishing, an equality
     * predicate for the board), because a closed list here would be a second copy of
     * {@code market_cities}. A well-formed code this board does not offer therefore passes — and
     * the board answers it with an empty page, from the clause it never drops.
     */
    @Test
    void aWellFormedCodeIsAcceptedWithoutAnyClaimThatThePlaceExists() {
        assertThat(MarketCityCodes.canonical("NOWHERE")).contains("NOWHERE");
        assertThat(MarketCityCodes.canonical("atlantis")).contains("ATLANTIS");
    }

    /** Everything that cannot be a code at all, answered with no statement and no exception. */
    @Test
    void anythingThatCannotBeACodeIsRejectedAndNothingThrows() {
        assertThatCode(() -> MarketCityCodes.canonical(null)).doesNotThrowAnyException();

        assertThat(MarketCityCodes.canonical(null)).isEmpty();
        assertThat(MarketCityCodes.canonical("")).isEmpty();
        assertThat(MarketCityCodes.canonical("   ")).isEmpty();
        // The free text V15 replaced, and the reason it did.
        assertThat(MarketCityCodes.canonical("28 May metro")).isEmpty();
        assertThat(MarketCityCodes.canonical("şəhər mərkəzi")).isEmpty();
        // A probe. The parameter is bound either way, so this is not about injection — it is about
        // not sending PostgreSQL something that cannot match a row.
        assertThat(MarketCityCodes.canonical("' OR 1=1 --")).isEmpty();
        assertThat(MarketCityCodes.canonical("BAKU'")).isEmpty();
        assertThat(MarketCityCodes.canonical("BAKU,GANJA")).isEmpty();
        assertThat(MarketCityCodes.canonical("BAKU%")).isEmpty();
        // Longer than market_cities.code, so it cannot be one. Bounded before any case mapping.
        assertThat(MarketCityCodes.canonical("X".repeat(MarketCityCodes.MAX_LENGTH + 1))).isEmpty();
        assertThat(MarketCityCodes.canonical("x".repeat(10_000))).isEmpty();
        // A code somebody else has already upper-cased with an Azerbaijani locale. Failing closed
        // is correct: the contract is to send the code as the picker gave it.
        assertThat(MarketCityCodes.canonical("İMİŞLİ")).isEmpty();
    }

    /**
     * <strong>A LABEL IS NOT REJECTED BY SHAPE, AND PRETENDING OTHERWISE WOULD BE THE BUG.</strong>
     * {@code "Bakı"} upper-cases under {@link Locale#ROOT} to {@code "BAKI"} — dotless ı maps to
     * plain I — which is pure ASCII and therefore code-SHAPED. There is no list here to check it
     * against, deliberately (a list here would be a second copy of {@code market_cities}), so it
     * passes this gate and is refused one step later by whoever owns that answer: a 400 from
     * {@code MarketCityCatalog} on publish, and an equality predicate that matches no row on the
     * board. What must never happen is the third outcome — treating it as "no filter at all".
     */
    @Test
    void aDiacriticLabelFoldsToSomethingCodeShapedAndIsRefusedByTheCatalogueInstead() {
        assertThat(MarketCityCodes.canonical("Bakı")).contains("BAKI");
        // ə is not ASCII under any case mapping, so this one does fail the shape check.
        assertThat(MarketCityCodes.canonical("Gəncə")).isEmpty();
    }

    @Test
    void theLongestRealCodeStillFitsTheBound() {
        assertThat(MarketCityCodes.canonical("MINGACHEVIR")).contains("MINGACHEVIR");
        assertThat("MINGACHEVIR".length()).isLessThanOrEqualTo(MarketCityCodes.MAX_LENGTH);
    }

    /**
     * <strong>The hazard, proved by making the default locale the one that breaks it.</strong>
     * Under an {@code az} or {@code tr} locale {@code "imishli".toUpperCase()} is {@code "İMİŞLİ"}
     * and {@code "ISMAYILLI".toLowerCase()} is a dotless-ı string — neither round-trips, so a code
     * case-mapped with the default locale stops matching the row it names on a machine whose locale
     * nobody thought about.
     *
     * <p>The default locale is global state; it is set and restored here, and nothing else in the
     * suite reads it.
     */
    @Test
    void aCodeIsNeverCaseMappedWithTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("az"));

            // The two worked examples, spelled out.
            assertThat(MarketCityCodes.canonical("imishli")).contains("IMISHLI");
            assertThat(MarketCityCodes.canonical("ismayilli")).contains("ISMAYILLI");
            // And the general statement: a code round-trips through lower case and back.
            for (String code : new String[] {"BAKU", "IMISHLI", "ISMAYILLI", "SHIRVAN", "ZARDAB"}) {
                assertThat(MarketCityCodes.canonical(code.toLowerCase(Locale.ROOT)))
                        .as(code + " under an az default locale")
                        .contains(code);
            }
        } finally {
            Locale.setDefault(original);
        }
    }
}
