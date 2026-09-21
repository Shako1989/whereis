package az.technest.whereis.marketplace.board.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the anonymous board. Every component is a value the seller typed and confirmed at
 * publish time.
 *
 * <p><strong>What is absent is the design.</strong> No {@code locationPath} or
 * {@code currentLocationId} (business rule 2, and structurally unreachable — the board's query
 * never touches {@code locations}). No {@code itemId}: it is the one id that would appear in BOTH
 * surfaces, and a separate listing id also means withdraw-and-republish mints a new public id,
 * which is what a seller who withdrew something actually wants. No {@code userId} or
 * {@code sellerId}: a stable seller id lets a scraper cluster every listing to one person. No
 * {@code updatedAt} of the item — {@code items.updated_at} moves when the owner MOVES the item, so
 * publishing it would broadcast private activity timing; {@code publishedAt} is the row's own
 * {@code created_at}.
 *
 * <p>Built by hand, never by MapStruct: {@code unmappedTargetPolicy=ERROR} protects TARGETS, not
 * sources, so a generated mapper would silently start carrying any field later added to both.
 */
public record PublicListingSummary(
        UUID id,
        String title,
        BigDecimal price,
        String currency,
        String city,
        String imageUrl,
        Instant publishedAt) {
}
