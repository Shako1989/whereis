package az.technest.whereis.location.dto;

import az.technest.whereis.location.LocationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateLocationRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        @NotNull LocationType type,
        UUID parentLocationId
) {
}
