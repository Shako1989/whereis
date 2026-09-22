package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.marketplace.seller.dto.ListingRequest;
import az.technest.whereis.marketplace.seller.dto.MyListingResponse;
import az.technest.whereis.space.SpaceType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * V15: the collection city is REFERENCE DATA. This IT covers the four things that can only be
 * proved end to end — the picker's body and its caching, the clean 4xx on a city this board does
 * not offer, the board filter failing CLOSED, and retirement.
 *
 * <p>Every assertion here is about a behaviour whose opposite is silent. An unknown code answered
 * with the whole board has a correct status and correct fields; a publish left to the foreign key
 * answers a generic 409 the client cannot branch on, after the public photo copy has been written;
 * and a retired city that disappeared from existing listings would rewrite what a seller said about
 * where their item is.
 */
class MarketplaceCityPickerIT extends AbstractIntegrationTest {

    private static final String PICKER = "/api/v1/market/cities";
    private static final String BOARD = "/api/v1/market/listings";

    /**
     * The whole list, in one anonymous request, with all three names on every row.
     *
     * <p>75 is asserted because it IS the claim: 11 cities of republic significance plus 64 rayons,
     * both levels of the first-order division. A half-seeded table makes publishing impossible for
     * everybody it omits, and the failure is invisible — the picker simply does not offer them.
     */
    @Test
    void thePickerServesAllSeventyFiveCitiesWithEveryNameAndNoToken() {
        ResponseEntity<JsonNode> response = rest.getForEntity(PICKER, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode cities = response.getBody().get("cities");
        assertThat(cities).hasSize(75);

        List<String> codes = new ArrayList<>();
        int previousOrder = Integer.MIN_VALUE;
        for (JsonNode city : cities) {
            codes.add(city.get("code").asText());
            // All three, always: a client that fell back to a blank label would render an empty row.
            assertThat(city.get("nameAz").asText()).isNotBlank();
            assertThat(city.get("nameEn").asText()).isNotBlank();
            assertThat(city.get("nameRu").asText()).isNotBlank();
            // The server sorts by the column, so the array arrives in picker order.
            assertThat(city.get("sortOrder").asInt()).isGreaterThan(previousOrder);
            previousOrder = city.get("sortOrder").asInt();
        }
        assertThat(codes).doesNotHaveDuplicates().startsWith("BAKU");
        assertThat(codes).contains("ABSHERON", "AGHDARA", "QOBUSTAN", "NAKHCHIVAN", "SHARUR");
    }

    /**
     * <strong>Azerbaijani collation, served rather than sorted.</strong> Xankəndi belongs between
     * Gəncə and Lənkəran because the alphabet runs … G Ğ H X I İ … — a naive sort anywhere in the
     * stack puts it after Yevlax, and each layer would get it wrong differently.
     */
    @Test
    void theOrderIsAzerbaijaniAndTheThreeLoadBearingLabelsArriveExactly() {
        JsonNode cities = rest.getForEntity(PICKER, JsonNode.class).getBody().get("cities");

        List<String> codes = new ArrayList<>();
        cities.forEach(city -> codes.add(city.get("code").asText()));
        assertThat(codes.indexOf("KHANKENDI")).isBetween(codes.indexOf("GANJA") + 1,
                codes.indexOf("LANKARAN") - 1);

        assertThat(labelOf(cities, "ABSHERON")).isEqualTo("Xırdalan (Abşeron)");
        assertThat(labelOf(cities, "QOBUSTAN")).isEqualTo("Qobustan (Mərəzə)");
        assertThat(labelOf(cities, "NAKHCHIVAN")).isEqualTo("Naxçıvan (şəhər)");
    }

    /**
     * <strong>Cached hard, and by a header rather than by a process-local snapshot.</strong> This
     * data has changed once in thirty years. The contrast with the board's own {@code no-store} is
     * the design: a stale listing is last week's price to somebody who cannot refresh out of it, a
     * stale administrative division is nothing.
     *
     * <p>It is on the board's chain, so it needs no token AND survives a rubbish one — that chain
     * has no JWT decoder at all, which is exactly why a picker opened by a client whose 15-minute
     * access token expired still works.
     */
    @Test
    void thePickerIsCachedPubliclyWhileTheBoardItselfStaysNoStore() {
        ResponseEntity<JsonNode> picker = rest.getForEntity(PICKER, JsonNode.class);

        String cacheControl = picker.getHeaders().getCacheControl();
        assertThat(cacheControl).contains("max-age=2592000").contains("public");
        assertThat(rest.getForEntity(BOARD, JsonNode.class).getHeaders().getCacheControl())
                .contains("no-store");

        HttpHeaders rubbish = new HttpHeaders();
        rubbish.set(HttpHeaders.AUTHORIZATION, "Bearer not-a-token-at-all");
        ResponseEntity<JsonNode> withBadToken = rest.exchange(PICKER, HttpMethod.GET,
                new HttpEntity<>(rubbish), JsonNode.class);
        assertThat(withBadToken.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * <strong>A CLEAN 4xx, NEVER A 500.</strong> Both shapes of "not a city this board offers" are
     * covered because they fail at different places and only one of them is obvious:
     * {@code NOWHERE} is code-shaped and missing from the table, while {@code "Bakı"} — the free
     * text V15 replaced — upper-cases under {@code Locale.ROOT} to {@code BAKI}, which is ASCII and
     * therefore also code-shaped, and is missing too ({@code BAKU} is the code). Neither may reach
     * {@code fk_listings_city}: mutation-checked, that path answers 409 {@code CONFLICT} instead —
     * a code the client cannot branch on, raised after the public photo copy has been written.
     */
    @Test
    void publishingACityThisBoardDoesNotOfferIsAFourHundredWithItsOwnCode() {
        Seller seller = newSeller();

        for (String city : List.of("NOWHERE", "Bakı", "28 May metro", "BAKU'", "")) {
            UUID itemId = itemWithPhoto(seller, "Esya " + UUID.randomUUID());

            ResponseEntity<JsonNode> refused = post(seller.token(),
                    "/api/v1/items/" + itemId + "/listing", listingRequest("Satilir", city),
                    JsonNode.class);

            assertThat(refused.getStatusCode())
                    .as("city=%s must be a client error", city)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            // A blank city never reaches the catalogue: @NotBlank answers it first, and that is a
            // VALIDATION_ERROR. Everything that could plausibly be a code gets the actionable code.
            assertThat(refused.getBody().get("code").asText())
                    .isIn("MARKET_CITY_UNKNOWN", "VALIDATION_ERROR");
            if (!city.isBlank()) {
                assertThat(refused.getBody().get("code").asText()).isEqualTo("MARKET_CITY_UNKNOWN");
            }
            // Nothing was written, and the item is still publishable with a real code.
            assertThat(jdbc.queryForObject(
                    "select count(*) from listings where item_id = ?", Integer.class, itemId))
                    .isZero();
        }
    }

    /**
     * The foreign key is still the backstop, and this proves it rather than trusting the service:
     * the only way to put an unknown code in that column is to bypass the application, and
     * PostgreSQL refuses.
     */
    @Test
    void theForeignKeyRefusesAnUnknownCodeEvenFromRawSql() {
        Seller seller = newSeller();
        UUID listingId = publish(seller, "Kod yoxlanisi " + UUID.randomUUID(), "GANJA");

        assertThatThrownBy(() -> jdbc.update("update listings set city = 'NOWHERE' where id = ?",
                listingId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select city from listings where id = ?", String.class,
                listingId)).isEqualTo("GANJA");
    }

    /**
     * <strong>THE ONE BEHAVIOUR HERE THAT CAN SILENTLY INVERT.</strong> An unknown city must filter
     * to NOTHING. Dropping the clause when a value is not recognised would answer a typo — or a
     * probe — with the entire board, with a correct status code and correct fields, and nothing
     * would ever report it as a bug.
     */
    @Test
    void anUnknownCityFiltersToNothingRatherThanToTheWholeBoard() {
        Seller seller = newSeller();
        String tag = "zz" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        UUID listingId = publish(seller, tag + " termos", "SHAKI");

        // The real code finds it; no city at all finds it.
        assertThat(boardIds(tag, "SHAKI")).containsExactly(listingId);
        assertThat(boardIds(tag, null)).containsExactly(listingId);

        // Everything else finds NOTHING — a well-formed but absent code, the free text that folds
        // into one, a label, a probe, a value longer than the column, and a real OTHER city.
        for (String city : List.of("NOWHERE", "BAKI", "Bakı", "baki seher merkezi", "' OR 1=1 --",
                "x".repeat(200), "GANJA")) {
            assertThat(boardIds(tag, city)).as("city=%s", city).isEmpty();
        }
    }

    /**
     * <strong>RETIREMENT — the capability that made this a table instead of a Java enum.</strong>
     * Three things must hold at once: the place leaves the PICKER, a NEW listing may no longer name
     * it, and every listing that already named it keeps its value and stays on the board, filterable
     * by that code. An enum could not have expressed any of this: removing the constant breaks the
     * existing rows, and keeping it keeps offering the place.
     */
    @Test
    void aRetiredCityLeavesThePickerWhileExistingListingsKeepIt() {
        Seller seller = newSeller();
        String tag = "zz" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        UUID listingId = publish(seller, tag + " palto", "NAFTALAN");

        // An operator's one-line retirement. Restored in the finally block: the whole suite shares
        // this database and this row.
        jdbc.update("update market_cities set active = false where code = 'NAFTALAN'");
        try {
            List<String> offered = new ArrayList<>();
            rest.getForEntity(PICKER, JsonNode.class).getBody().get("cities")
                    .forEach(city -> offered.add(city.get("code").asText()));
            assertThat(offered).hasSize(74).doesNotContain("NAFTALAN");

            // The existing listing is untouched, still public, and still reachable by its code.
            assertThat(jdbc.queryForObject("select city from listings where id = ?", String.class,
                    listingId)).isEqualTo("NAFTALAN");
            assertThat(boardIds(tag, "NAFTALAN")).containsExactly(listingId);

            // But a NEW one may not name it, with the code that tells the client to re-read the
            // list it has been caching for a month.
            UUID itemId = itemWithPhoto(seller, "Ikinci esya " + UUID.randomUUID());
            ResponseEntity<JsonNode> refused = post(seller.token(),
                    "/api/v1/items/" + itemId + "/listing", listingRequest("Satilir", "NAFTALAN"),
                    JsonNode.class);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getBody().get("code").asText()).isEqualTo("MARKET_CITY_UNKNOWN");
        } finally {
            jdbc.update("update market_cities set active = true where code = 'NAFTALAN'");
        }
    }

    /** The seller's own view serves the CODE, and so does the board — the label is the client's. */
    @Test
    void bothSidesServeTheCodeAndNeitherServesALocalisedName() {
        Seller seller = newSeller();
        UUID listingId = publish(seller, "Kod " + UUID.randomUUID(), "SUMQAYIT");

        JsonNode mine = get(seller.token(), "/api/v1/listings/" + listingId, JsonNode.class)
                .getBody();
        assertThat(mine.get("city").asText()).isEqualTo("SUMQAYIT");

        JsonNode publicDetail = rest.getForEntity(BOARD + "/" + listingId, JsonNode.class).getBody();
        assertThat(publicDetail.get("city").asText()).isEqualTo("SUMQAYIT");
        // No localised name anywhere in a listing body: the picker is the only place names live.
        assertThat(publicDetail.toString()).doesNotContain("Sumqayıt").doesNotContain("Сумгаит");
    }

    // ---------------------------------------------------------------- helpers

    private record Seller(String token, UUID locationId) {
    }

    private Seller newSeller() {
        String token = registerAndGetToken();
        UUID spaceId = createSpace(token, "Ev", SpaceType.HOME).id();
        UUID roomId = createLocation(token, spaceId, "Yataq otagi", LocationType.ROOM, null).id();
        UUID shelfId = createLocation(token, spaceId, "Skaf", LocationType.FURNITURE, roomId).id();
        return new Seller(token, shelfId);
    }

    private UUID itemWithPhoto(Seller seller, String name) {
        UUID itemId = post(seller.token(), "/api/v1/items",
                new CreateItemRequest(name, null, null, seller.locationId()), ItemResponse.class)
                .getBody().id();
        uploadJpeg(seller.token(), itemId, true);
        return itemId;
    }

    private UUID publish(Seller seller, String title, String city) {
        UUID itemId = itemWithPhoto(seller, title);
        ResponseEntity<MyListingResponse> created = post(seller.token(),
                "/api/v1/items/" + itemId + "/listing", listingRequest(title, city),
                MyListingResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().id();
    }

    private static ListingRequest listingRequest(String title, String city) {
        return new ListingRequest(title, "Az islenmis, tam saz veziyyetde, qutusu ile birlikde.",
                new BigDecimal("250.00"), "+994 50 123 45 67", city, null);
    }

    /**
     * The board, filtered by this test's own title tag so "the board is empty" is never the
     * assertion — the suite shares one database and one board.
     */
    private List<UUID> boardIds(String tag, String city) {
        String url = BOARD + "?q=" + tag + (city == null ? "" : "&city=" + encode(city));
        JsonNode page = rest.getForEntity(url, JsonNode.class).getBody();
        List<UUID> ids = new ArrayList<>();
        page.get("listings").forEach(listing -> ids.add(UUID.fromString(listing.get("id").asText())));
        return ids;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String labelOf(JsonNode cities, String code) {
        for (JsonNode city : cities) {
            if (code.equals(city.get("code").asText())) {
                return city.get("nameAz").asText();
            }
        }
        throw new AssertionError(code + " is not in the picker");
    }
}
