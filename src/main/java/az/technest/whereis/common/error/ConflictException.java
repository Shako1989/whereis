package az.technest.whereis.common.error;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {

    public ConflictException(ErrorCode code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
