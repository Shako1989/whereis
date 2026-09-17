package az.technest.whereis.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * @param spaceId    optional answer to a previous {@code NEEDS_CONFIRMATION} (BR-2): the space the
 *                   user picked from {@code candidateSpaces}. When present it settles the space
 *                   outright and the AI is not consulted about it; ownership is still checked, and
 *                   a space belonging to someone else is a 404 like every other miss. Omit it
 *                   normally — the message itself is expected to say where.
 * @param locationId optional pinned destination (BR-7): the exact location the caller has already
 *                   chosen, from the "filed here recently" chips. When present it settles the
 *                   destination outright — no space resolution, no chain resolution, and
 *                   <strong>no location can be created by this request at all</strong>, which is
 *                   the point: it is the only path on which a wrong or duplicate location is
 *                   structurally impossible rather than merely unlikely. The model is still called
 *                   for the item name and description, and whatever it says about the place is
 *                   recorded for provenance and otherwise unused; when it named somewhere else,
 *                   the response says so via {@code messagePlaceIgnored}. Ownership is checked and
 *                   a foreign id is a 404.
 *                   <p>Mutually exclusive with {@code spaceId} (400 {@code VALIDATION_ERROR}) — a
 *                   location already implies its space, so sending both can only express a
 *                   contradiction.
 */
public record RememberRequest(
        @NotBlank @Size(max = 1000) String message,
        UUID spaceId,
        UUID locationId
) {
}
