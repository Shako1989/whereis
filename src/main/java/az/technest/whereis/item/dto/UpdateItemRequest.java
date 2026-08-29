package az.technest.whereis.item.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Location changes go through the move endpoint, never through update. */
public record UpdateItemRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 2000) String description,
        @Size(max = 100) String category,
        Boolean archived
) {
}
