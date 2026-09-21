package az.technest.whereis.marketplace.board;

import az.technest.whereis.marketplace.board.dto.CreateReportRequest;
import az.technest.whereis.marketplace.board.dto.PublicListingDetail;
import az.technest.whereis.marketplace.board.dto.PublicListingPage;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The anonymous marketplace board.
 *
 * <p><strong>There is no {@code CurrentUser.id()} in this class and there never may be.</strong>
 * {@link MarketBoardSecurityConfig} serves this path with a chain that has no JWT decoder, so a
 * request here is ALWAYS anonymous — even one carrying a perfectly valid token. Calling
 * {@code CurrentUser.id()} from here would be a 500, and
 * {@code MarketplacePublicSurfaceArchTest} makes it a build failure instead of a runtime
 * discovery. An endpoint that needs a principal belongs at a different path.
 *
 * <p>{@code ResponseEntity} is used only to set {@code Cache-Control: no-store}. <strong>The
 * reason for it changed in V14 and the header did not.</strong> It used to be that the body
 * embedded a presigned URL — a short-lived credential a proxy must not cache and re-serve after it
 * has expired. Published photos now live at permanent public addresses and carry their own
 * one-day {@code Cache-Control}, so that argument is gone; what remains is the listing DATA, where
 * a stale cache serves a withdrawn listing, a moderated one, or last week's price to a stranger who
 * cannot refresh their way out of it. The image is cacheable and the listing is not, which is the
 * correct split. {@code LegalPagesController} and {@code RtdnController} are the precedent for
 * deviating from the no-ResponseEntity house style when there are headers to set.
 */
@RestController
@RequestMapping(MarketBoardController.PATH)
@RequiredArgsConstructor
public class MarketBoardController {

    /** Shared with the security chain's {@code securityMatcher}, so the two cannot drift. */
    public static final String PATH = "/api/v1/market";

    private final MarketBoardService boardService;

    @GetMapping("/listings")
    public ResponseEntity<PublicListingPage> browse(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(boardService.browse(q, city, page, size));
    }

    @GetMapping("/listings/{listingId}")
    public ResponseEntity<PublicListingDetail> detail(@PathVariable UUID listingId) {
        return noStore(boardService.detail(listingId));
    }

    /**
     * 202 for everything that PARSES — including an unknown listing id and one already hidden. A
     * row is written only when the listing is currently visible and under its daily cap, and the
     * caller is told none of that: an anonymous endpoint must have nothing to say to a prober.
     * 400 is reserved for a body that cannot bind at all, which is Jackson and bean validation
     * rather than a policy decision.
     */
    @PostMapping("/listings/{listingId}/reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void report(@PathVariable UUID listingId, @Valid @RequestBody CreateReportRequest request) {
        boardService.report(listingId, request);
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
