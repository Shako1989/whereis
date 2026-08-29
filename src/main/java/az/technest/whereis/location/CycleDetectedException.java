package az.technest.whereis.location;

import az.technest.whereis.common.error.ConflictException;
import az.technest.whereis.common.error.ErrorCode;

public class CycleDetectedException extends ConflictException {

    public CycleDetectedException() {
        super(ErrorCode.CYCLE_DETECTED, "The change would create a cycle in the location hierarchy");
    }
}
