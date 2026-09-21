package az.technest.whereis.marketplace;

/**
 * Why an operator barred an ACCOUNT from the public board. Pinned to
 * {@code ck_blocked_sellers_reason} byte for byte by {@code ListingEnumsTest}.
 *
 * <p><strong>Deliberately not {@link ListingHiddenReason}.</strong> That enum answers "what is wrong
 * with this listing" — {@code WRONG_OR_MISLEADING} and {@code ABUSE_REPORTS} are statements about
 * one row's text and one row's complaints, and neither describes a person. This one answers "what
 * is wrong with this seller", which is a judgement about a pattern across listings. Sharing one
 * enum would have forced every future value to make sense at both levels, and the first value that
 * did not would be routed to {@code OTHER} on every row.
 *
 * <p>Shown to the SELLER, for the same reason {@code hidden_reason} is: a marketplace that goes
 * silent is indistinguishable from a marketplace that is broken, and a seller who does not know
 * they were sanctioned cannot stop doing the thing that got them sanctioned. The operator's
 * free-text note is NOT shown — it is internal, untranslated, and written for a colleague.
 */
public enum SellerBlockReason {

    SCAM_OR_FRAUD,

    /** Repeatedly offering things the board does not admit, rather than one listing that did. */
    PROHIBITED_ITEMS,

    OFFENSIVE_CONTENT,

    /** The volume case: the same thing ten times, or a board full of one account. */
    SPAM_OR_BULK_LISTINGS,

    /** Already had listings hidden, and carried on. The reason a per-listing switch was not enough. */
    REPEATED_VIOLATIONS,

    OTHER
}
