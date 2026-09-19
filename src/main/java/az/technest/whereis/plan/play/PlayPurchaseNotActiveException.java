package az.technest.whereis.plan.play;

import az.technest.whereis.common.error.ConflictException;
import az.technest.whereis.common.error.ErrorCode;

/**
 * Google knows the purchase, but it does not entitle: EXPIRED, PAUSED, ON_HOLD, PENDING, an
 * unmapped state, or an entitling state whose term is already over.
 *
 * <p>409 and retryable. A PENDING payment becomes ACTIVE later, so the client keeps the token and
 * re-posts; it is deliberately NOT a 400, because a 400 tells the client to drop a token that may
 * be about to become a paid subscription.
 */
public class PlayPurchaseNotActiveException extends ConflictException {

    public PlayPurchaseNotActiveException(String message) {
        super(ErrorCode.PLAY_PURCHASE_NOT_ACTIVE, message);
    }
}
