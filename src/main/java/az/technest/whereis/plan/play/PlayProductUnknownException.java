package az.technest.whereis.plan.play;

import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.common.error.ErrorCode;

/**
 * The client named a product id that is not in {@code whereis.plans.*}. Refused BEFORE any Google
 * call, and never guessed: an unknown product id is not defaulted to FREE and not defaulted to the
 * lowest paid tier.
 */
public class PlayProductUnknownException extends BadRequestException {

    public PlayProductUnknownException(String message) {
        super(ErrorCode.PLAY_PRODUCT_UNKNOWN, message);
    }
}
