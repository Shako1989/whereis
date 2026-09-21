package az.technest.whereis.marketplace.board.dto;

import java.util.List;

/**
 * Deliberately NOT Spring's {@code Page}: there is no total and therefore no {@code COUNT(*)}.
 *
 * <p>Two reasons at once. The count would be the most expensive thing on an endpoint whose cost is
 * not bounded by any account, on every anonymous request; and a total tells a scraper exactly how
 * complete their mirror is. {@code hasMore} is what a board UI actually needs, and it costs one
 * extra row rather than a second scan.
 */
public record PublicListingPage(
        List<PublicListingSummary> listings,
        boolean hasMore,
        int page,
        int size) {
}
