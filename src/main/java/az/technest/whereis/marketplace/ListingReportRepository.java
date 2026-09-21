package az.technest.whereis.marketplace;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Written by the only unauthenticated endpoint in this application, read only by an operator
 * through SQL. There is no read endpoint and no finder that returns report CONTENT to any client.
 */
public interface ListingReportRepository extends JpaRepository<ListingReport, UUID> {

    /**
     * The per-listing flood cap, applied instead of storing a reporter identity. Serves off
     * {@code ix_listing_reports_listing_reported}.
     */
    long countByListingIdAndReportedAtAfter(UUID listingId, Instant since);

    /**
     * Closes every still-open report against every listing of one seller, in ONE statement, when
     * that seller is blocked. Without it the operator's queue re-surfaces forever exactly the
     * complaints they have just acted on, which is what makes a queue query useless after the first
     * incident — the reason {@code reviewed_at} and {@code review_outcome} exist at all.
     *
     * <p>{@code listing_reports} has no {@code user_id}, by design: the subquery through
     * {@code listings} is the only linkage the table offers, and it is the same one
     * {@code PlayNotificationRepository#deleteAllLinkedToUser} uses for the same reason. It serves
     * off {@code ix_listing_reports_pending} (partial on {@code reviewed_at IS NULL}) and
     * {@code ix_listings_user}.
     *
     * <p><strong>No {@code clearAutomatically}</strong> — this codebase's standing lesson: it
     * detaches managed entities the caller is still holding, and the caller here has just saved a
     * {@code BlockedSeller} in the same transaction. {@code flushAutomatically} is what this
     * statement actually needs.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ListingReport r
               set r.reviewedAt = :at, r.reviewOutcome = :outcome
             where r.reviewedAt is null
               and r.listingId in (select l.id from Listing l where l.userId = :sellerId)
            """)
    int closeOpenReportsAgainstSeller(@Param("sellerId") UUID sellerId,
            @Param("outcome") ListingReportOutcome outcome, @Param("at") Instant at);
}
