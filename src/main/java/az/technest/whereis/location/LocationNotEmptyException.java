package az.technest.whereis.location;

import az.technest.whereis.common.error.ConflictException;
import az.technest.whereis.common.error.ErrorCode;

public class LocationNotEmptyException extends ConflictException {

    public LocationNotEmptyException(String message) {
        super(ErrorCode.LOCATION_NOT_EMPTY, message);
    }
}
