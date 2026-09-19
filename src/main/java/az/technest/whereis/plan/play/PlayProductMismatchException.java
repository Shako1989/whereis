package az.technest.whereis.plan.play;

import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.common.error.ErrorCode;

/**
 * NOT "the client claimed a different product than Google reports" — that alone is legitimate (a
 * deferred downgrade keeps the OLD product on the token until the term ends, so the client posts
 * what it launched and Google answers with the previous product). This is reserved for the case
 * where NO line item on the purchase maps to any configured product at all, which is the only
 * shape that is genuinely unclaimable.
 *
 * <p>That narrowing matters because the client's rule for a 400 is to DROP the token: a purchase
 * the user paid for must never be discarded by a classification decision. The tier is therefore
 * resolved from GOOGLE's line item, never from the client's claim.
 */
public class PlayProductMismatchException extends BadRequestException {

    public PlayProductMismatchException(String message) {
        super(ErrorCode.PLAY_PRODUCT_MISMATCH, message);
    }
}
