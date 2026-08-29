package az.technest.whereis.location.dto;

import az.technest.whereis.location.LocationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** parentLocationId is applied as given: null re-parents the location to the space root. */
public record UpdateLocationRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        @NotNull LocationType type,
        UUID parentLocationId
) {
}
