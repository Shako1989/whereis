package az.technest.whereis.assistant;

import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public class AiAssistantException extends ApiException {

    public AiAssistantException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, ErrorCode.AI_UNAVAILABLE, message, cause);
    }

    public AiAssistantException(String message) {
        super(HttpStatus.BAD_GATEWAY, ErrorCode.AI_UNAVAILABLE, message);
    }
}
