package az.technest.whereis.common.error;

public enum ErrorCode {
    VALIDATION_ERROR,
    USER_NOT_FOUND,
    SPACE_NOT_FOUND,
    LOCATION_NOT_FOUND,
    ITEM_NOT_FOUND,
    FILE_NOT_FOUND,
    EMAIL_IN_USE,
    DUPLICATE_NAME,
    INVALID_CREDENTIALS,
    TOKEN_INVALID,
    TOKEN_EXPIRED,
    INVALID_LOCATION_HIERARCHY,
    CYCLE_DETECTED,
    LOCATION_NOT_EMPTY,
    SPACE_NOT_EMPTY,
    PLAN_LIMIT_REACHED,
    // Play Billing (V10). PLAY_* describe what Google said about a purchase; PLAN_* describe what
    // this application decided about an account.
    PLAY_UNAVAILABLE,
    PLAY_PURCHASE_INVALID,
    PLAY_PURCHASE_NOT_ACTIVE,
    PLAY_PRODUCT_UNKNOWN,
    PLAY_PRODUCT_MISMATCH,
    // Billing is switched off in this deployment (whereis.play.provider=disabled): Google was
    // never asked, so this says nothing about the purchase and can never imply a grant.
    PLAY_BILLING_NOT_CONFIGURED,
    PLAN_PURCHASE_NOT_OWNED,
    FILE_TOO_LARGE,
    UNSUPPORTED_MEDIA_TYPE,
    STORAGE_ERROR,
    AI_UNAVAILABLE,
    AI_NOT_IMPLEMENTED,

    // Marketplace (V12). LISTING_* describe what this application decided about a listing;
    // ITEM_* describe the state of the item it points at, and are answered by item-facing
    // endpoints as well as by publish.
    LISTING_NOT_FOUND,
    LISTING_ALREADY_ACTIVE,
    // Mark-sold, withdraw or edit attempted on a SOLD or WITHDRAWN row. Terminal is terminal:
    // re-listing is a new row, so that a report filed against one price and one description can
    // never come to describe another.
    LISTING_NOT_ACTIVE,
    // Editing while an operator has the listing off the board. The route back is withdraw and
    // publish a corrected listing — a fresh row judged on its own merits, leaving the moderated
    // one intact as evidence.
    LISTING_HIDDEN,
    LISTING_PHOTO_REQUIRED,
    // The ONE new 400 that is not VALIDATION_ERROR, because it carries a NUMBER the client must
    // render ("at least 40 characters"). server.error.include-binding-errors is `never`, so a
    // generic code would tell the user nothing at all.
    LISTING_DESCRIPTION_TOO_SHORT,
    ITEM_ARCHIVED,
    // Archiving an item, or deleting its published cover photo, while it is on the board.
    ITEM_LISTED,

    // The public board is the only surface whose cost is not bounded by an account.
    RATE_LIMITED,

    CONFLICT,
    INTERNAL_ERROR
}
