package az.technest.whereis.assistant;

import java.util.List;

/**
 * Raw, UNTRUSTED output of an AI provider. Must pass {@link InterpretationValidator}
 * before anything downstream sees it; it never touches the database directly.
 */
public record PlacementInterpretation(
        String itemName,
        String itemDescription,
        String spaceName,
        List<LocationSegment> locations,
        Double confidence
) {
}
