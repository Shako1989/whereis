package az.technest.whereis.marketplace.board.dto;

import az.technest.whereis.marketplace.ListingReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What an anonymous visitor may say about a listing — the only unauthenticated write in this
 * application.
 *
 * <p>Nothing identifies the reporter, here or in the row it becomes. The per-IP limit lives in
 * memory and stores nothing; the per-listing cap needs no identity at all.
 */
public record CreateReportRequest(
        @NotNull ListingReportReason reason,
        @Size(max = 500) String note) {
}
