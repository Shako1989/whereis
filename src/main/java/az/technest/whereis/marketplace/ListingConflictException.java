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
