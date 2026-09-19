package az.technest.whereis.plan.play;

import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * The Play Developer API could not be reached or answered with a failure that says nothing about
 * the purchase: transport failure, 5xx, timeout, or an auth failure. <strong>Retryable, and
 * nothing has been written.</strong> Mirrors {@code AiAssistantException}, which is the same
 * 502-shaped "the provider is the problem, not you".
 */
public class PlayApiException extends ApiException {

    public PlayApiException(String message) {
        super(HttpStatus.BAD_GATEWAY, ErrorCode.PLAY_UNAVAILABLE, message);
    }

    public PlayApiException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, ErrorCode.PLAY_UNAVAILABLE, message, cause);
    }
}
