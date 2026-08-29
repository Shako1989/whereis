package az.technest.whereis.assistant;

import az.technest.whereis.assistant.InterpretationValidator.ValidatedPlacement;
import az.technest.whereis.item.ItemService;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.location.ChainResult;
import az.technest.whereis.location.LocationService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single transactional mutation step of the assistant flow. Runs AFTER the AI call
 * (no DB transaction may span provider latency) and only on validated, entity-resolved input.
 */
@Component
@RequiredArgsConstructor
public class PlacementExecutor {

    private final LocationService locationService;
    private final ItemService itemService;

    public record ExecutionResult(ItemResponse item, List<String> createdLocations) {
    }

    @Transactional
    public ExecutionResult place(UUID userId, UUID spaceId, ValidatedPlacement placement, String note) {
        ChainResult chain = locationService.resolveOrCreateChain(userId, spaceId, placement.segments());
        ItemResponse item = itemService.createAt(userId, chain.leaf().getId(), placement.itemName(),
                placement.description(), null, note);
        return new ExecutionResult(item, chain.createdNames());
    }
}
