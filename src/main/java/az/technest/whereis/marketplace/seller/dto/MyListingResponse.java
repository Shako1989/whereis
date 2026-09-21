package az.technest.whereis.marketplace.seller.dto;

import az.technest.whereis.marketplace.Listing;
import az.technest.whereis.marketplace.ListingCurrency;
import az.technest.whereis.marketplace.ListingHiddenReason;
import az.technest.whereis.marketplace.ListingStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The SELLER's view of their own listing — richer than the public one, and a different record.
 *
 * <p>It carries {@code itemId} and {@code hiddenReason}, which the public DTO must never have: the
 * seller needs to know which of their items this is, and a listing that vanished from the board
 * without explanation is how a user concludes the application is broken. The operator's free-text
 * {@code hidden_note} is deliberately absent — it is internal, untranslated, and written for a
 * colleague rather than for the person it is about.
 */
public record MyListingResponse(
        UUID id,
        UUID itemId,
        ListingStatus status,
        boolean hidden,
        ListingHiddenReason hiddenReason,
        String title,
        String description,
        BigDecimal price,
        ListingCurrency currency,
        String contactPhone,
        String city,
        UUID coverFileId,
        Instant createdAt,
        Instant endedAt) {

    /** The only factory. */
    public static MyListingResponse of(Listing listing) {
        return new MyListingResponse(
                listing.getId(),
                listing.getItemId(),
                listing.getStatus(),
                listing.isHidden(),
                listing.getHiddenReason(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getPriceAmount(),
                listing.getPriceCurrency(),
                listing.getContactPhone(),
                listing.getCity(),
                listing.getCoverFileId(),
                listing.getCreatedAt(),
                listing.getEndedAt());
    }
}
