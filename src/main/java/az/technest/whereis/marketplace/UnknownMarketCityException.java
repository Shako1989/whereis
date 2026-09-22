package az.technest.whereis.marketplace;

import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.common.error.ErrorCode;

/**
 * 400: the publish or edit named a collection city that is not one this board offers.
 *
 * <p><strong>A 4xx the client can act on, rather than the one a raw constraint violation
 * produces.</strong> Without this check the code reaches the INSERT, {@code fk_listings_city}
 * raises a 23503, and {@code GlobalExceptionHandler} answers <em>409 CONFLICT / code
 * {@code CONFLICT}</em> — "The request conflicts with existing data". Measured, not assumed: that
 * is what the mutation check produced. Three things are wrong with it and none is the status code:
 * the client cannot branch on it (it is the same code a duplicate row gets), it says nothing about
 * what to fix, and it arrives only AFTER the metadata-stripped public photo copy has been written
 * to the world-readable bucket. It is also logged at DEBUG, so nobody ever sees it.
 *
 * <p><strong>Its own code rather than {@code VALIDATION_ERROR}, because the client can ACT on it:
 * re-fetch the picker and ask again.</strong> The realistic cause is not a typo — the client picks
 * from a list — but a picker cached for a month while a place was retired, which is the one
 * consequence of caching that table hard. The message therefore names the endpoint to re-read and
 * never echoes the rejected value back.
 */
public class UnknownMarketCityException extends BadRequestException {

    public UnknownMarketCityException() {
        super(ErrorCode.MARKET_CITY_UNKNOWN,
                "Choose the collection city from the current list (GET /api/v1/market/cities).");
    }
}
