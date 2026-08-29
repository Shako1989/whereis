package az.technest.whereis.assistant;

import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public class AiNotImplementedException extends ApiException {

    public AiNotImplementedException(String message) {
        super(HttpStatus.NOT_IMPLEMENTED, ErrorCode.AI_NOT_IMPLEMENTED, message);
    }
}
