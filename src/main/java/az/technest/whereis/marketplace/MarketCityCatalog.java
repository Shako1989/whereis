package az.technest.whereis.marketplace;

import az.technest.whereis.marketplace.board.dto.MarketCitiesResponse;
import az.technest.whereis.marketplace.board.dto.MarketCityResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The collection-city list, read by the two surfaces that need it: the anonymous picker endpoint
 * and the seller's publish path. ONE class so that "what the picker offers" and "what a publish
 * accepts" cannot become two different answers — the same mistake {@code PlanLimitEnforcer} exists
 * to prevent for tiers, where the screen and the wall must compute the rule once.
 *
 * <p><strong>Nothing is memoized in this process, deliberately.</strong> The hard caching lives in
 * the HTTP response ({@code MarketCityController}), where it costs a client one request a month and
 * costs this server nothing; an in-process snapshot would add a second, invisible cache whose only
 * effect is that an operator retiring a place with one UPDATE would keep serving it until somebody
 * restarted the application. 75 rows in {@code sort_order} is not a query worth outsmarting.
 */
@Service
@RequiredArgsConstructor
public class MarketCityCatalog {

    private final MarketCityRepository repository;

    /**
     * Every place a new listing may name, in picker order, with all three labels.
     *
     * <p>The order comes from {@code sort_order} — see {@link MarketCity#getSortOrder()} for why it
     * is data and not a runtime sort. Nothing here sorts, compares or case-maps a label.
     */
    @Transactional(readOnly = true)
    public MarketCitiesResponse picker() {
        List<MarketCityResponse> cities = repository.findAllByActiveTrueOrderBySortOrderAsc().stream()
                .map(city -> new MarketCityResponse(city.getCode(), city.getNameAz(),
                        city.getNameEn(), city.getNameRu(), city.getSortOrder()))
                .toList();
        return new MarketCitiesResponse(cities);
    }

    /**
     * The publish-time gate: the canonical code, or a 400 that tells the client to re-read the
     * picker.
     *
     * <p>Both failures are the SAME answer on purpose — a value that cannot be a code at all, and a
     * well-formed code this board does not currently offer (unknown, or retired since the client
     * cached the list) — because the client's next action is identical in both cases and telling
     * the two apart would describe the catalogue to anyone who asks.
     *
     * <p>This is what keeps {@code fk_listings_city} from being the thing that answers: without it
     * an unknown code reaches the INSERT and comes back as a generic 409 {@code CONFLICT} — the
     * same code a duplicate row gets, carrying no advice, raised after the public photo copy has
     * already been written.
     */
    @Transactional(readOnly = true)
    public String requireSelectable(String rawCode) {
        String code = MarketCityCodes.canonical(rawCode)
                .orElseThrow(UnknownMarketCityException::new);
        if (!repository.existsByCodeAndActiveTrue(code)) {
            throw new UnknownMarketCityException();
        }
        return code;
    }
}
