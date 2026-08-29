package az.technest.whereis.storage;

import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public class StorageException extends ApiException {

    public StorageException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, ErrorCode.STORAGE_ERROR, message, cause);
    }
}
