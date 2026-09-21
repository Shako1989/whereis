package az.technest.whereis.marketplace;

/**
 * What an anonymous visitor says is wrong with a listing. Pinned to
 * {@code ck_listing_reports_reason}.
 *
 * <p>{@link #WRONG_OR_MISLEADING} covers the case with no technical control behind it: a seller
 * publishing somebody else's phone number. Without SMS verification nothing stops that at write
 * time, so the report plus the kill-switch is the whole mitigation.
 */
public enum ListingReportReason {

    PROHIBITED_ITEM,
    SCAM_OR_FRAUD,
    OFFENSIVE_CONTENT,
    /** Wrong price, wrong photo, or a contact number that is not the seller's to publish. */
    WRONG_OR_MISLEADING,
    OTHER
}
