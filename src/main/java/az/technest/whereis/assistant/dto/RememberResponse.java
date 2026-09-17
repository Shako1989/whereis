package az.technest.whereis.assistant.dto;

import az.technest.whereis.item.dto.ItemResponse;
import java.util.List;
import java.util.UUID;

/**
 * @param messagePlaceIgnored CREATED only, and only on the pinned path (BR-7): the sentence named a
 *                            place, the caller had pinned a different one, and the pin won. The pin
 *                            is deliberately never overridden by the sentence — that would restore
 *                            exactly the model trust the pin exists to remove — so this flag is how
 *                            a stale pin becomes visible instead of silent. Always {@code false}
 *                            otherwise.
 */
public record RememberResponse(
        Status status,
        String message,
        ItemResponse item,
        List<String> createdLocations,
        List<SpaceOption> candidateSpaces,
        boolean messagePlaceIgnored
) {

    public enum Status { CREATED, NEEDS_CONFIRMATION, NOT_UNDERSTOOD }

    public record SpaceOption(UUID id, String name) {
    }

    public static RememberResponse created(ItemResponse item, List<String> createdLocations, String message) {
        return created(item, createdLocations, message, false);
    }

    public static RememberResponse created(ItemResponse item, List<String> createdLocations, String message,
                                           boolean messagePlaceIgnored) {
        return new RememberResponse(Status.CREATED, message, item, createdLocations, List.of(), messagePlaceIgnored);
    }

    public static RememberResponse needsConfirmation(String message, List<SpaceOption> candidates) {
        return new RememberResponse(Status.NEEDS_CONFIRMATION, message, null, List.of(), candidates, false);
    }

    public static RememberResponse notUnderstood(String message) {
        return new RememberResponse(Status.NOT_UNDERSTOOD, message, null, List.of(), List.of(), false);
    }
}
