package az.technest.whereis.marketplace;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
