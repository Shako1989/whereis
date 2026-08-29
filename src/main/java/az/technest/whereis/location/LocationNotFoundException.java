package az.technest.whereis.location;

import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.error.NotFoundException;

public class LocationNotFoundException extends NotFoundException {

    public LocationNotFoundException() {
        super(ErrorCode.LOCATION_NOT_FOUND, "Location not found");
    }
}
