package az.technest.whereis.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * @param spaceId optional answer to a previous {@code NEEDS_CONFIRMATION}: the space the user
 *                picked from {@code candidateSpaces}. When present it settles the space outright
 *                and the AI is not consulted about it; ownership is still checked, and a space
 *                belonging to someone else is a 404 like every other miss. Omit it normally —
 *                the message itself is expected to say where.
 */
public record RememberRequest(
        @NotBlank @Size(max = 1000) String message,
        UUID spaceId
) {
}
