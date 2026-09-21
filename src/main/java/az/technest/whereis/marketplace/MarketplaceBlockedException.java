package az.technest.whereis.marketplace;

import az.technest.whereis.common.error.ConflictException;
import az.technest.whereis.common.error.ErrorCode;

/**
 * 409: this ACCOUNT is barred from the public board, so nothing about the request can be fixed.
 *
 * <p>Distinct from {@link ListingConflictException#hidden()} on purpose, and the distinction is the
 * whole feature: {@code LISTING_HIDDEN} says "this row was judged, withdraw it and publish a
 * corrected one", which is actionable advice. Reusing it here would tell a blocked seller to
 * re-publish, they would, it would be refused, and the application would read as broken.
 *
 * <p>The message NAMES THE REASON and states plainly that the private inventory is untouched. Both
 * halves are deliberate: a sanction nobody explains cannot be complied with, and a seller whose
 * listings vanish will otherwise assume their items went too. The operator's internal note is never
 * included.
 */
public class MarketplaceBlockedException extends ConflictException {

    private MarketplaceBlockedException(String message) {
        super(ErrorCode.MARKETPLACE_BLOCKED, message);
    }

    public static MarketplaceBlockedException of(SellerBlockReason reason) {
        return new MarketplaceBlockedException(
                "Your marketplace access is suspended (" + MarketplaceRules.describe(reason)
                        + "). Your items, photos and spaces are untouched and still private to you.");
    }
}
