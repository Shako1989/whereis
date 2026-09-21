package az.technest.whereis.marketplace;

/**
 * What a seller has decided about their own listing. Pinned to {@code ck_listings_status} byte for
 * byte by {@link az.technest.whereis.marketplace.ListingStatusTest}.
 *
 * <p><strong>SOLD and WITHDRAWN are TERMINAL.</strong> A listing is never re-activated; re-listing
 * inserts a new row. The reason is {@code listing_reports}: reopening a row would let a report
 * filed against one price and one description silently apply to another, and the report is the
 * only record of what was actually published. Same reasoning as
 * {@code item_location_history.location_path_snapshot} — the artefact a complaint names must not
 * be rewritable.
 *
 * <p>The operator's kill-switch is NOT a value here. It is {@code listings.hidden_at}, a separate
 * nullable column, because the operator's decision and the seller's are independent facts: as a
 * status, a seller marking a hidden listing sold would silently un-hide it.
 */
public enum ListingStatus {

    ACTIVE,
    SOLD,
    WITHDRAWN;

    /**
     * Half of the public visibility rule — the other half is {@code hidden_at IS NULL}. Use
     * {@link Listing#isPubliclyVisible()} rather than this alone; a test pins that the JPQL the
     * board runs admits exactly the statuses this returns true for.
     */
    public boolean isPubliclyVisible() {
        return this == ACTIVE;
    }

    /** ACTIVE is the only state a seller may act on, and the only one that occupies an item's slot. */
    public boolean isTerminal() {
        return this != ACTIVE;
    }
}
