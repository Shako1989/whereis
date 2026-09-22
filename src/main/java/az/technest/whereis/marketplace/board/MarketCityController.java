package az.technest.whereis.marketplace.board;

import az.technest.whereis.marketplace.MarketCityCatalog;
import az.technest.whereis.marketplace.board.dto.MarketCitiesResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/market/cities} — the collection-city picker, and the only endpoint in this
 * application whose answer is the same for everybody.
 *
 * <p><strong>On the BOARD's chain, not the authenticated one</strong>, because both audiences need
 * it: an anonymous visitor filtering the board, and a seller filling in the publish form. Mounting
 * it under {@link MarketBoardController#PATH} means one copy, no token, and the same subtree the
 * board's {@code @Order(1)} chain already serves with no JWT decoder — so it works while a client's
 * 15-minute access token is expired, which is precisely when a picker gets opened. It is a GET
 * under that matcher, so {@code MarketBoardSecurityConfig} permits it with no change, and
 * {@code MarketBoardRateLimitFilter} meters it out of the same per-address READ budget as a board
 * page. That is correct and costs a real client one request a month, because of the header below.
 *
 * <p><strong>CACHED HARD, which is the point of serving a lookup table rather than localised
 * text.</strong> {@code public, max-age=30d} — this data has changed once in thirty years (Ağdərə,
 * December 2023) — plus a {@code Vary}-free body, because the response does not depend on
 * {@code Accept-Language}: every row carries all three names and the client reads its own. A locale-
 * negotiated variant would have needed {@code Vary: Accept-Language}, three cache entries and a
 * fallback rule, and it would have put the server in charge of text it does not own.
 *
 * <p>The staleness this buys is bounded and harmless in both directions: a place ADDED after the
 * client cached simply is not offered yet, and a place RETIRED is still offered — which the publish
 * path answers with {@code 400 MARKET_CITY_UNKNOWN}, the one error code that tells a client to
 * re-read this list. That is why the refusal exists and why it is not a generic validation error.
 *
 * <p>Deliberately NOT {@code no-store} like the board's own responses: those carry listing data,
 * where a stale cache serves a withdrawn listing or last week's price. This carries an
 * administrative division.
 */
@RestController
@RequestMapping(MarketBoardController.PATH + "/cities")
@RequiredArgsConstructor
public class MarketCityController {

    /** Long enough that a real client asks once a month; short enough that a retirement lands. */
    private static final Duration CACHE_FOR = Duration.ofDays(30);

    private final MarketCityCatalog catalog;

    @GetMapping
    public ResponseEntity<MarketCitiesResponse> cities() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_FOR).cachePublic())
                .body(catalog.picker());
    }
}
