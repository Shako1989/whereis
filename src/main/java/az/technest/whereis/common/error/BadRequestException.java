package az.technest.whereis.common.error;

import org.springframework.http.HttpStatus;

public class BadRequestException extends ApiException {

    public BadRequestException(ErrorCode code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
