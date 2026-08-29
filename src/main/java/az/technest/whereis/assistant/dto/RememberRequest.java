package az.technest.whereis.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RememberRequest(
        @NotBlank @Size(max = 1000) String message
) {
}
