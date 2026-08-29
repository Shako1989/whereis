package az.technest.whereis.space.dto;

import az.technest.whereis.space.SpaceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateSpaceRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        @NotNull SpaceType type
) {
}
