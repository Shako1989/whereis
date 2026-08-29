package az.technest.whereis.assistant.dto;

import az.technest.whereis.item.dto.ItemResponse;
import java.util.List;
import java.util.UUID;

public record RememberResponse(
        Status status,
        String message,
        ItemResponse item,
        List<String> createdLocations,
        List<SpaceOption> candidateSpaces
) {

    public enum Status { CREATED, NEEDS_CONFIRMATION, NOT_UNDERSTOOD }

    public record SpaceOption(UUID id, String name) {
    }

    public static RememberResponse created(ItemResponse item, List<String> createdLocations, String message) {
        return new RememberResponse(Status.CREATED, message, item, createdLocations, List.of());
    }

    public static RememberResponse needsConfirmation(String message, List<SpaceOption> candidates) {
        return new RememberResponse(Status.NEEDS_CONFIRMATION, message, null, List.of(), candidates);
    }

    public static RememberResponse notUnderstood(String message) {
        return new RememberResponse(Status.NOT_UNDERSTOOD, message, null, List.of(), List.of());
    }
}
