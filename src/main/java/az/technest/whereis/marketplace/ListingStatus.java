package az.technest.whereis.marketplace;

/**
 * What a seller has decided about their own listing. Pinned to {@code ck_listings_status} byte for
 * byte by {@code ListingEnumsTest}.
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
     * One THIRD of the public visibility rule. The others are {@code hidden_at IS NULL} (this row's
     * operator kill-switch) and "the seller is not blocked" (V13, an account fact no listing column
     * carries). Use {@link Listing#isPubliclyVisible()} rather than this alone, and read
     * {@code MarketBoardDao.VISIBLE} for the whole rule — {@code MarketBoardVisibilityTest} pins
     * that the SQL the board runs states all three clauses.
     */
    public boolean isPubliclyVisible() {
        return this == ACTIVE;
    }

    /** ACTIVE is the only state a seller may act on, and the only one that occupies an item's slot. */
    public boolean isTerminal() {
        return this != ACTIVE;
    }
}
