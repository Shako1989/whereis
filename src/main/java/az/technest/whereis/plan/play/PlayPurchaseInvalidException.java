package az.technest.whereis.plan.play;

import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Google does not know this token: a 404, or a token that is not a purchase of this package. The
 * client must DROP the token — no amount of retrying will make Google recognise it.
 */
public class PlayPurchaseInvalidException extends ApiException {

    public PlayPurchaseInvalidException(String message) {
        super(HttpStatus.BAD_REQUEST, ErrorCode.PLAY_PURCHASE_INVALID, message);
    }

    public PlayPurchaseInvalidException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, ErrorCode.PLAY_PURCHASE_INVALID, message, cause);
    }
}
