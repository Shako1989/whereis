package az.technest.whereis.marketplace;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.migration.Migrations;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * V15's seed, read out of the migration the way the database will.
 *
 * <p><strong>Why this test exists at all.</strong> The other coded columns in this schema are
 * varchar + CHECK pinned to a Java enum, and {@code ListingEnumsTest} guards them because a CHECK
 * and an enum are two copies of one list. The city has ONE copy — the table — so there is nothing
 * to compare it against, and {@code fk_listings_city} makes drift impossible rather than merely
 * detectable. What can still go wrong is the SEED: a half-applied list makes publishing impossible
 * for everybody it omits, a mistyped code freezes a wrong identifier into every row that picks it,
 * and a "tidied" label re-opens a misfile the parentheses exist to prevent. None of those fail a
 * build or throw at runtime.
 */
class MarketCitySeedTest {

    /**
     * One seeded row. {@code name_ru} is captured like the rest even though nothing in the backend
     * reads it: the picker serves all three names so the CLIENT can read its own, which is what
     * keeps this server out of the localisation business.
     */
    private record SeededCity(String code, String nameAz, String nameEn, String nameRu, int sortOrder) {
    }

    private static final Pattern ROW = Pattern.compile(
            "\\(\\s*'([^']*)',\\s*'([^']*)',\\s*'([^']*)',\\s*'([^']*)',\\s*(\\d+)\\s*\\)");

    private static List<SeededCity> seededCities() {
        String insert = insertStatement();
        Matcher matcher = ROW.matcher(insert);
        List<SeededCity> cities = new ArrayList<>();
        while (matcher.find()) {
            cities.add(new SeededCity(matcher.group(1), matcher.group(2), matcher.group(3),
                    matcher.group(4), Integer.parseInt(matcher.group(5))));
        }
        return cities;
    }

    /** The one INSERT into {@code market_cities} across every migration, comments already stripped. */
    private static String insertStatement() {
        Pattern insert = Pattern.compile(
                "INSERT INTO market_cities[^;]+;", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        List<String> statements = new ArrayList<>();
        for (Migrations.Migration migration : Migrations.all()) {
            Matcher matcher = insert.matcher(migration.sql());
            while (matcher.find()) {
                statements.add(matcher.group());
            }
        }
        assertThat(statements)
                .as("exactly one seed of market_cities — a second would be a merge accident")
                .hasSize(1);
        return statements.getFirst();
    }

    /**
     * <strong>75, and the number is the whole claim.</strong> 11 cities of republic significance
     * plus 64 rayons — both levels of the first-order division. A list covering only one level
     * cannot name where most of the country is, and the failure is invisible: every listing simply
     * lands in the nearest big place the picker offers. A HALF-SEEDED table is worse still, because
     * publishing from an omitted place is refused with a 400 nobody can act on.
     */
    @Test
    void theSeedLandsAllSeventyFiveFirstOrderUnits() {
        List<SeededCity> cities = seededCities();

        assertThat(cities).hasSize(75);
        assertThat(cities).extracting(SeededCity::code).doesNotHaveDuplicates();
    }

    /**
     * A code is an IDENTIFIER, not a label — {@code ck_market_cities_code_shape} says so in the
     * database and this says so about the data that has to satisfy it. A diacritic in a code would
     * put Azerbaijani's İ/ı hazard inside the stored value rather than only in the parsing of it.
     */
    @Test
    void everyCodeIsAsciiUpperSnakeAndFitsTheColumn() {
        assertThat(seededCities()).allSatisfy(city -> {
            assertThat(city.code())
                    .as("a stable identifier, not a label")
                    .matches("[A-Z][A-Z_]*[A-Z]");
            assertThat(city.code().length()).isLessThanOrEqualTo(MarketCityCodes.MAX_LENGTH);
            // And it survives the round trip the board's filter performs on it.
            assertThat(MarketCityCodes.canonical(city.code().toLowerCase(Locale.ROOT)))
                    .contains(city.code());
        });
    }

    /** Three labels per row, all present, all inside {@code varchar(64)}. */
    @Test
    void everyRowCarriesAllThreeNames() {
        assertThat(seededCities()).allSatisfy(city -> {
            assertThat(city.nameAz()).isNotBlank().hasSizeLessThanOrEqualTo(64);
            assertThat(city.nameEn()).isNotBlank().hasSizeLessThanOrEqualTo(64);
            assertThat(city.nameRu()).isNotBlank().hasSizeLessThanOrEqualTo(64);
        });
    }

    /**
     * <strong>THE THREE LOAD-BEARING LABELS.</strong> Each prevents one specific misfile, and each
     * is exactly the kind of thing a later "tidy up the picker" commit shortens. A wrong collection
     * city is worse than an absent one, because a buyer travels for it.
     */
    @Test
    void theThreeParentheticalLabelsSurviveExactly() {
        List<SeededCity> cities = seededCities();

        // Without Xırdalan leading, much of the country's fourth-largest city picks Bakı.
        assertThat(named(cities, "ABSHERON").nameAz()).isEqualTo("Xırdalan (Abşeron)");
        assertThat(named(cities, "ABSHERON").nameEn()).isEqualTo("Khirdalan (Absheron)");
        // This rayon is in Dağlıq Şirvan and is NOT the Qobustan rock-art park, which sits inside
        // Bakı's Qaradağ district — the likeliest misfile in the list.
        assertThat(named(cities, "QOBUSTAN").nameAz()).isEqualTo("Qobustan (Mərəzə)");
        assertThat(named(cities, "QOBUSTAN").nameEn()).isEqualTo("Gobustan (Maraza)");
        // Stops Ordubad and Şərur sellers picking the city.
        assertThat(named(cities, "NAKHCHIVAN").nameAz()).isEqualTo("Naxçıvan (şəhər)");
        assertThat(named(cities, "NAKHCHIVAN").nameEn()).isEqualTo("Nakhchivan (city)");
    }

    /**
     * The codes a reader may reasonably think are mistakes. Asserted so that "fixing" one is a test
     * failure rather than a silent change to what a stored row means.
     */
    @Test
    void theDeliberateCodeChoicesAreStillTheChoicesThatWereMade() {
        List<String> codes = seededCities().stream().map(SeededCity::code).toList();

        // The code names the administrative UNIT (Abşeron rayonu; ISO 3166-2 AZ-ABS) while the
        // label leads with the town. The one code in the list that was a judgement call, and it was
        // reversible only until the first row was written.
        assertThat(codes).contains("ABSHERON").doesNotContain("KHIRDALAN");
        // Created in December 2023 out of parts of Ağdam, Kəlbəcər and Tərtər, and absent from
        // ISO 3166-2 — the one entry any list assembled from an older source is missing.
        assertThat(codes).contains("AGHDARA");
        // Şəki, Lənkəran and Yevlax are ONE unit each in the current division, so they appear once.
        // Only the out-of-date ISO list still splits the city from the rayon.
        assertThat(codes).contains("SHAKI", "LANKARAN", "YEVLAKH");
        // Bakı's 12 city rayons are a SECOND level and are deliberately absent: including them
        // mixes levels and takes the list to 87. A dependent second field is the shape to use.
        assertThat(codes).doesNotContain("BINAQADI", "NASIMI", "SABUNCHU", "YASAMAL");
    }

    /**
     * <strong>The picker's order is DATA, is TOTAL, and leaves room at both ends.</strong> No ties
     * (a duplicate would make two clients list the same 75 places in two different orders, which
     * nobody would file as a bug); cities of republic significance ahead of every rayon; Bakı at the
     * head because roughly a quarter of the country is there; and a gap between rows so that adding
     * a place is an INSERT rather than a renumbering of rows whose codes are already frozen.
     *
     * <p>The reserved bands below 100 and above 9000 are for the product gap this list genuinely
     * has and does NOT fill: a seller who will meet anywhere or who ships nationwide. That will be
     * a separate nullable field or a distinct NON-GEOGRAPHIC value — never a 76th place — and it
     * will want to sort either first or last.
     */
    @Test
    void theSortOrderIsATotalOrderWithRoomLeftAtBothEnds() {
        List<SeededCity> cities = seededCities();
        List<Integer> orders = cities.stream().map(SeededCity::sortOrder).toList();

        assertThat(orders).doesNotHaveDuplicates().isSorted();
        assertThat(named(cities, "BAKU").sortOrder()).isEqualTo(orders.getFirst());

        List<SeededCity> republicCities = cities.stream().filter(c -> c.sortOrder() < 1000).toList();
        assertThat(republicCities).hasSize(11);
        assertThat(republicCities).extracting(SeededCity::code)
                .containsExactlyInAnyOrder("BAKU", "GANJA", "KHANKENDI", "LANKARAN", "MINGACHEVIR",
                        "NAFTALAN", "NAKHCHIVAN", "SHAKI", "SHIRVAN", "SUMQAYIT", "YEVLAKH");
        assertThat(cities.stream().filter(c -> c.sortOrder() >= 1000).toList()).hasSize(64);

        assertThat(orders).allSatisfy(order -> assertThat(order)
                .as("the bands reserved for a future non-geographic value")
                .isBetween(100, 8999));
        // Steps rather than 1, 2, 3: slotting a place between two existing ones must not renumber
        // a row whose code is already frozen into listings.
        for (int i = 1; i < orders.size(); i++) {
            assertThat(orders.get(i) - orders.get(i - 1))
                    .as("gap before " + cities.get(i).code())
                    .isGreaterThanOrEqualTo(10);
        }
    }

    /**
     * <strong>Azerbaijani collation, pre-computed — the reason {@code sort_order} exists.</strong>
     * The alphabet is A B C Ç D E Ə F G Ğ H X I İ J K Q L M N O Ö P R S Ş T U Ü V Y Z, so X comes
     * BEFORE L and ə before f: Xankəndi belongs between Gəncə and Lənkəran, and Ağdaş before
     * Ağdərə. A naive {@code ORDER BY name_az} in SQL, a {@code sorted()} in Java or a client-side
     * sort all get this wrong, and each would get it wrong differently.
     *
     * <p>Spot-checked rather than fully re-derived: re-implementing the collator here would just be
     * asserting the generator against itself.
     */
    @Test
    void theOrderFollowsTheAzerbaijaniAlphabetAndNotTheLatinOne() {
        List<SeededCity> cities = seededCities();

        assertThat(sortOf(cities, "GANJA"))
                .isLessThan(sortOf(cities, "KHANKENDI"));      // Gəncə  < Xankəndi
        assertThat(sortOf(cities, "KHANKENDI"))
                .isLessThan(sortOf(cities, "LANKARAN"));       // Xankəndi < Lənkəran  (X before L)
        assertThat(sortOf(cities, "AGHDASH"))
                .isLessThan(sortOf(cities, "AGHDARA"));        // Ağdaş  < Ağdərə      (a before ə)
        assertThat(sortOf(cities, "BEYLAGAN"))
                .isLessThan(sortOf(cities, "BARDA"));          // Beyləqan < Bərdə     (e before ə)
        assertThat(sortOf(cities, "GORANBOY"))
                .isLessThan(sortOf(cities, "GOYCHAY"));        // Goranboy < Göyçay    (o before ö)
        assertThat(sortOf(cities, "KHACHMAZ"))
                .isLessThan(sortOf(cities, "IMISHLI"));        // Xaçmaz < İmişli      (X before İ)
        assertThat(sortOf(cities, "QUSAR"))
                .isLessThan(sortOf(cities, "LACHIN"));         // Qusar  < Laçın       (Q before L)
        assertThat(sortOf(cities, "SHARUR"))
                .isLessThan(sortOf(cities, "SHUSHA"));         // Şərur  < Şuşa
    }

    private static SeededCity named(List<SeededCity> cities, String code) {
        return cities.stream().filter(city -> city.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError(code + " is missing from the seed"));
    }

    private static int sortOf(List<SeededCity> cities, String code) {
        return named(cities, code).sortOrder();
    }
}
