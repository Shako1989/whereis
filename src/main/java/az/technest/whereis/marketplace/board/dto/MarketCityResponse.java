package az.technest.whereis.marketplace.board.dto;

/**
 * One row of the collection-city picker: the code a listing stores, and the label in all three
 * languages this product ships.
 *
 * <p><strong>All three names rather than one negotiated by locale</strong>, which is the decision
 * that keeps this endpoint cacheable and keeps the server out of the localisation business. A
 * locale-negotiated response varies by {@code Accept-Language} (so it needs a {@code Vary} header,
 * three cache entries and a fallback rule for a language nobody configured), and it would make the
 * server the owner of text it does not otherwise own. One response, every language, and the client
 * reads its own — and a client whose user switches language renders the change instantly, offline,
 * from the copy it already has.
 *
 * <p>{@code sortOrder} is on the wire as well as implied by the array's order, so a client that
 * caches these rows in its own database can reproduce the picker's order with an {@code ORDER BY}
 * instead of depending on insertion order surviving a local store.
 *
 * <p>There is no {@code active} component: only active rows are served, so its absence IS the
 * statement. A listing published before a place was retired keeps that code, and the board still
 * serves it — the client renders a code it cannot find in the picker as the code itself.
 */
public record MarketCityResponse(
        String code,
        String nameAz,
        String nameEn,
        String nameRu,
        int sortOrder) {
}
