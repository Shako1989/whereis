package az.technest.whereis.marketplace.board.dto;

import java.util.List;

/**
 * The whole collection-city picker in one body, in picker order.
 *
 * <p>An OBJECT and not a bare JSON array: a top-level array has nowhere to put the next thing this
 * lookup needs (a version marker, or the dependent Baku-district list the design already
 * anticipates), and changing an array into an object later is a breaking change for every client.
 *
 * <p>No {@code hasMore}, no page and no size. This is a closed list of 75 rows that changes about
 * once a decade; paginating it would make a client assemble something it is meant to cache whole.
 */
public record MarketCitiesResponse(List<MarketCityResponse> cities) {
}
