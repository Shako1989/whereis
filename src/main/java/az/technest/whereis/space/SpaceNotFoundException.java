package az.technest.whereis.space;

import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.error.NotFoundException;

public class SpaceNotFoundException extends NotFoundException {

    public SpaceNotFoundException() {
        super(ErrorCode.SPACE_NOT_FOUND, "Space not found");
    }
}
