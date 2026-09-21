package az.technest.whereis.marketplace.board.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One listing, in full, to an anonymous visitor. Same absences as {@link PublicListingSummary},
 * plus the two fields a buyer needs to act: the description and the contact phone.
 *
 * <p>The phone is published to unauthenticated callers by an explicit product decision, and both
 * legal pages say so in both languages. It is the number the SELLER typed on this listing — there
 * is no phone number on a whereis account and this feature deliberately did not add one, so a
 * seller may give a work number for one item and a personal one for another.
 */
public record PublicListingDetail(
        UUID id,
        String title,
        String description,
        BigDecimal price,
        String currency,
        String city,
        String contactPhone,
        String imageUrl,
        Instant publishedAt) {
}
