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
    CONFLICT,
    INTERNAL_ERROR
}
