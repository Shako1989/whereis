package az.technest.whereis.assistant;

import az.technest.whereis.assistant.InterpretationValidator.ValidatedPlacement;
import az.technest.whereis.item.ItemService;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.location.ChainResult;
import az.technest.whereis.location.Location;
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

    /**
     * @param spaceId the space the item ended up in. Reported back rather than assumed by the
     *                caller, because on the pinned path (BR-7) only the executor knows it — it
     *                falls out of the location lookup that proves ownership.
     */
    public record ExecutionResult(ItemResponse item, List<String> createdLocations, UUID spaceId) {
    }

    @Transactional
    public ExecutionResult place(UUID userId, UUID spaceId, ValidatedPlacement placement, String note) {
        ChainResult chain = locationService.resolveOrCreateChain(userId, spaceId, placement.segments());
        ItemResponse item = itemService.createAt(userId, chain.leaf().getId(), placement.itemName(),
                placement.description(), null, note);
        return new ExecutionResult(item, chain.createdNames(), spaceId);
    }

    /**
     * BR-7: the caller already chose the exact destination, so there is no placement to execute —
     * only an item to file. {@code resolveOrCreateChain}, the only auto-creation path in the
     * system, is never entered and nothing in the location tree can change; {@code
     * createdLocations} is therefore always empty by construction rather than by luck.
     *
     * <p>It takes the name and description rather than a {@link ValidatedPlacement} on purpose:
     * on this path no model ran, so there is no interpretation to validate and fabricating a
     * "validated placement" out of a literal string would put a lie in the type.
     *
     * <p>The lookup is here and not in the service because it must share the transaction with the
     * item insert: a location that vanishes between the ownership check and the insert would
     * otherwise pass the check and then fail the FK.
     */
    @Transactional
    public ExecutionResult placeAt(UUID userId, UUID locationId, String itemName, String description,
                                   String note) {
        Location target = locationService.requireOwned(userId, locationId);
        ItemResponse item = itemService.createAt(userId, target.getId(), itemName, description, null, note);
        return new ExecutionResult(item, List.of(), target.getSpaceId());
    }
}
