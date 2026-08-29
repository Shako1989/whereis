package az.technest.whereis.item.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record MoveItemRequest(
        @NotNull UUID locationId,
        @Size(max = 500) String note
) {
}
