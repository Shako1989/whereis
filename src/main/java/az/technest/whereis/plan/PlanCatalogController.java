package az.technest.whereis.plan;

import az.technest.whereis.plan.dto.PlanLadderRowResponse;
import az.technest.whereis.plan.dto.PlanLimitsResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/plans} — the ladder itself: every tier a client may render, with its real
 * numbers and its real Play product id, in ladder order.
 *
 * <p>Authenticated but account-independent, and it costs ZERO statements: it is pure configuration.
 * This is what lets the plan screen draw the ladder without hardcoding limits or product ids, and
 * it is also where the client gets its tier ORDERING from — a client that re-encoded the ladder
 * would be deciding a fact only the server owns, and the failure mode of getting it wrong is
 * offering a downgrade as an upgrade.
 *
 * <p>{@code UNLIMITED} is deliberately absent: it is not purchasable, and listing it would invite a
 * client to render it as an option. A caller whose current tier is therefore NOT in this list
 * (an operator grant, or a tier a stale build has never heard of) must be offered nothing.
 */
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class PlanCatalogController {

    private final PlanCatalog catalog;

    @GetMapping
    public List<PlanLadderRowResponse> ladder() {
        return catalog.ladder().stream()
                .map(tier -> new PlanLadderRowResponse(tier, catalog.of(tier).productId(),
                        new PlanLimitsResponse(catalog.spaceLimit(tier), catalog.itemLimit(tier))))
                .toList();
    }
}
