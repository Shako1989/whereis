package az.technest.whereis.marketplace;

/**
 * Why an operator took a listing off the board. Pinned to {@code ck_listings_hidden_reason}.
 *
 * <p>Shown to the SELLER: hiding somebody's listing without telling them is how a user concludes
 * the application is broken. The operator's free-text {@code hidden_note} is NOT shown — it is
 * internal, and an untranslated note rendered in a UI is worse than none.
 */
public enum ListingHiddenReason {

    PROHIBITED_ITEM,
    SCAM_OR_FRAUD,
    OFFENSIVE_CONTENT,
    WRONG_OR_MISLEADING,
    /** Enough reports to act on before any single one was judged. */
    ABUSE_REPORTS,
    OTHER
}
