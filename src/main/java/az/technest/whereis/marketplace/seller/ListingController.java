package az.technest.whereis.marketplace.seller;

import az.technest.whereis.common.security.CurrentUser;
import az.technest.whereis.marketplace.ListingService;
import az.technest.whereis.marketplace.ListingStatus;
import az.technest.whereis.marketplace.seller.dto.ListingRequest;
import az.technest.whereis.marketplace.seller.dto.MyListingResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The seller's own listings. On the MAIN chain, so every request here is authenticated and the
 * user id comes from the JWT subject — the exact opposite of {@code MarketBoardController}, which
 * is why the two live in different packages behind different security chains.
 *
 * <p>Publishing is item-scoped ({@code /items/{itemId}/listing}), mirroring
 * {@code ItemFileController}: the ownership check is on the ITEM, so a foreign id is the
 * established 404 rather than an empty result. Everything afterwards is listing-scoped, because a
 * listing outlives its ACTIVE state and an item may have several over time.
 */
@RestController
@RequiredArgsConstructor
public class ListingController {

    private final ListingService listingService;

    @PostMapping("/api/v1/items/{itemId}/listing")
    @ResponseStatus(HttpStatus.CREATED)
    public MyListingResponse publish(@PathVariable UUID itemId,
            @Valid @RequestBody ListingRequest request) {
        return listingService.publish(CurrentUser.id(), itemId, request);
    }

    @GetMapping("/api/v1/listings/{listingId}")
    public MyListingResponse get(@PathVariable UUID listingId) {
        return listingService.get(CurrentUser.id(), listingId);
    }

    @PutMapping("/api/v1/listings/{listingId}")
    public MyListingResponse update(@PathVariable UUID listingId,
            @Valid @RequestBody ListingRequest request) {
        return listingService.update(CurrentUser.id(), listingId, request);
    }

    /** Withdraw. A DELETE rather than a status PUT because it is what the client calls it. */
    @DeleteMapping("/api/v1/listings/{listingId}")
    public MyListingResponse withdraw(@PathVariable UUID listingId) {
        return listingService.end(CurrentUser.id(), listingId, ListingStatus.WITHDRAWN);
    }

    @PostMapping("/api/v1/listings/{listingId}/sold")
    public MyListingResponse markSold(@PathVariable UUID listingId) {
        return listingService.end(CurrentUser.id(), listingId, ListingStatus.SOLD);
    }

    @GetMapping("/api/v1/users/me/listings")
    public Page<MyListingResponse> mine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean activeOnly) {
        return listingService.list(CurrentUser.id(), page, size, activeOnly);
    }
}
