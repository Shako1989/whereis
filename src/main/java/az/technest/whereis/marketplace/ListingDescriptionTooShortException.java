package az.technest.whereis.marketplace;

import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.common.error.ErrorCode;

/**
 * The one field rule in this feature that earns its own code rather than the generic
 * {@code VALIDATION_ERROR}: it carries a NUMBER the client has to render, and
 * {@code server.error.include-binding-errors} is {@code never}, so a generic code would leave the
 * user with "validation error" and no idea what to change.
 */
public class ListingDescriptionTooShortException extends BadRequestException {

    public ListingDescriptionTooShortException(int actual) {
        super(ErrorCode.LISTING_DESCRIPTION_TOO_SHORT,
                "Describe the item in at least " + MarketplaceRules.MIN_DESCRIPTION_LENGTH
                        + " characters so buyers know what they are getting — you have " + actual + ".");
    }
}
