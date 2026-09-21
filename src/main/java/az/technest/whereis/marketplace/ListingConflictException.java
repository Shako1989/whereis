package az.technest.whereis.marketplace;

import az.technest.whereis.common.error.ConflictException;
import az.technest.whereis.common.error.ErrorCode;

/**
 * The 409 family for a well-formed request the ACCOUNT STATE does not permit — this API's
 * established distinction from 400, and the client branches on {@code code}.
 */
public class ListingConflictException extends ConflictException {

    private ListingConflictException(ErrorCode code, String message) {
        super(code, message);
    }

    public static ListingConflictException alreadyActive() {
        return new ListingConflictException(ErrorCode.LISTING_ALREADY_ACTIVE,
                "This item is already listed. Withdraw the existing listing first.");
    }

    public static ListingConflictException notActive() {
        return new ListingConflictException(ErrorCode.LISTING_NOT_ACTIVE,
                "This listing has already ended. Publish a new one instead.");
    }

    public static ListingConflictException hidden() {
        return new ListingConflictException(ErrorCode.LISTING_HIDDEN,
                "This listing was removed by a moderator and cannot be edited. "
                        + "Withdraw it and publish a corrected one.");
    }

    public static ListingConflictException photoRequired() {
        return new ListingConflictException(ErrorCode.LISTING_PHOTO_REQUIRED,
                "Add a photo to this item before listing it.");
    }

    /**
     * The publish-time refusal when a photo's metadata cannot be stripped (V14).
     *
     * <p>The message names the fix rather than the cause, because the cause is either an exotic
     * file or a bug in the rewriter and the seller can act on neither. What it must never become is
     * a publish that stores the original bytes: the whole point of the strip is that a listing
     * exposes a city and not the coordinates the camera recorded.
     */
    public static ListingConflictException photoUnpublishable() {
        return new ListingConflictException(ErrorCode.LISTING_PHOTO_UNPUBLISHABLE,
                "This photo could not be prepared for publishing. Choose another photo for this listing.");
    }

    public static ListingConflictException itemArchived() {
        return new ListingConflictException(ErrorCode.ITEM_ARCHIVED,
                "Unarchive this item before listing it.");
    }

    /** Raised by the ITEM and STORAGE paths, not by publish — hence the outward-facing wording. */
    public static ListingConflictException itemListed(String action) {
        return new ListingConflictException(ErrorCode.ITEM_LISTED,
                "This item is on the marketplace. Withdraw the listing before you " + action + ".");
    }
}
