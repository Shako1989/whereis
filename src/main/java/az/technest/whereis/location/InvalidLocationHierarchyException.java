package az.technest.whereis.location;

import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.common.error.ErrorCode;

public class InvalidLocationHierarchyException extends BadRequestException {

    public InvalidLocationHierarchyException(String message) {
        super(ErrorCode.INVALID_LOCATION_HIERARCHY, message);
    }
}
