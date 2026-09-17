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
 *                   destination outright, and the decision that follows is that
 *                   <strong>no model is called at all and this message IS the item name</strong>,
 *                   stored exactly as typed with a null description. Once the place is chosen there
 *                   is nothing to interpret — and interpreting anyway is what made a pinned
 *                   "kabel 20A" answer NOT_UNDERSTOOD, since a bare noun phrase is legitimately not
 *                   a placement statement. No space resolution, no chain resolution, and
 *                   <strong>no location can be created by this request at all</strong>: that is the
 *                   point, and it is the only path on which a wrong or duplicate location is
 *                   structurally impossible rather than merely unlikely.
 *                   <p>The text must still be a usable item name (non-blank, at most 120
 *                   characters, {@code InterpretationValidator}'s name charset). A failure answers
 *                   NOT_UNDERSTOOD rather than 400: the client renders that status with the message
 *                   and a manual fallback, and the user did nothing malformed. That charset is also
 *                   what keeps a question out, with no model and in any language.
 *                   <p>A full sentence sent with a pin becomes an item named after the whole
 *                   sentence. Accepted and tested, not a bug.
 *                   <p>Ownership is checked and a foreign id is a 404.
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
