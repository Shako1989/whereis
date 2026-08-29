package az.technest.whereis.item;

import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.error.NotFoundException;

public class ItemNotFoundException extends NotFoundException {

    public ItemNotFoundException() {
        super(ErrorCode.ITEM_NOT_FOUND, "Item not found");
    }
}
