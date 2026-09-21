package az.technest.whereis.marketplace;

import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.error.NotFoundException;

/**
 * 404, and the SAME 404 for three different truths: never existed, withdrawn by its seller, hidden
 * by an operator. Distinguishing them on the public board would turn the endpoint into an oracle —
 * it would tell an abuser their listing had been killed rather than merely ignored, and it would
 * tell a scraper which ids once existed.
 */
public class ListingNotFoundException extends NotFoundException {

    public ListingNotFoundException() {
        super(ErrorCode.LISTING_NOT_FOUND, "Listing not found");
    }
}
