package az.technest.whereis.item.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateItemRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 2000) String description,
        @Size(max = 100) String category,
        @NotNull UUID locationId
) {
}
