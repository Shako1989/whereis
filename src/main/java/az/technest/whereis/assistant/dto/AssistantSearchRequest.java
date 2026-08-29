package az.technest.whereis.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssistantSearchRequest(
        @NotBlank @Size(max = 500) String query
) {
}
