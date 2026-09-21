package az.technest.whereis.marketplace.moderation;

import az.technest.whereis.common.security.CurrentUser;
import az.technest.whereis.marketplace.moderation.dto.BlockSellerRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's two actions on a seller.
 *
 * <p><strong>THE PATH IS NOT UNDER {@code /api/v1/market/}, AND THAT IS NOT COSMETIC.</strong>
 * {@code MarketBoardSecurityConfig} claims {@code /api/v1/market/**} with an {@code @Order(1)}
 * chain that has NO {@code oauth2ResourceServer} and ends in {@code anyRequest().denyAll()}. A
 * moderation route mounted there would be served by that chain: {@code CurrentUser.id()} would
 * throw {@code IllegalStateException} on a request that carried a perfectly good token — a 500 and
 * an ERROR log rather than the 401 or 403 anyone would expect — and an unmatched POST would answer
 * 403 for a reason that has nothing to do with authorization. Mounted at {@code /api/v1/moderation}
 * it falls to the catch-all chain, where {@code anyRequest().authenticated()} decodes the JWT and
 * {@code CurrentUser} works. An IT asserts the anonymous 401 so a later move cannot pass silently.
 *
 * <p>Authorization is the {@link ModerationProperties} e-mail allowlist, checked in the SERVICE
 * rather than here: it needs a database read, and the answer is the moderator's e-mail, which is
 * also the audit value. Controllers in this codebase resolve the caller and delegate — they never
 * touch a repository, which {@code OwnershipScopingArchTest} enforces globally.
 *
 * <p><strong>There is deliberately no GET.</strong> Reading the block list and the report queue is
 * documented SQL ({@code deploy/README.md} Step 12), for the same reason grants are: a read surface
 * that shows one account's data to another needs a permission model this feature does not add.
 * ACTING is what has to leave a record.
 */
@RestController
@RequestMapping(SellerModerationController.PATH)
@RequiredArgsConstructor
public class SellerModerationController {

    /**
     * Outside the board's {@code securityMatcher} on purpose — see the class javadoc. A constant so
     * the IT that pins it against {@code MarketBoardController.PATH} cannot drift from the mapping.
     */
    public static final String PATH = "/api/v1/moderation";

    private final SellerBlockService sellerBlocks;

    /**
     * Bars a seller from the public board. Their listings leave it on the next request; their items,
     * spaces, locations and photos are not touched at all.
     *
     * <p>Keyed on the SELLER's user id rather than on a listing id, because the sanction is about
     * the account and must outlive any particular row — a listing can be withdrawn or its item
     * deleted between the report and the decision. Step 12 documents the one-line query from a
     * reported listing to its seller.
     */
    @PostMapping("/sellers/{sellerId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@PathVariable UUID sellerId, @Valid @RequestBody BlockSellerRequest request) {
        sellerBlocks.block(CurrentUser.id(), sellerId, request);
    }

    /** Lifts the block. Every listing the account still has returns to the board unchanged. */
    @DeleteMapping("/sellers/{sellerId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@PathVariable UUID sellerId) {
        sellerBlocks.unblock(CurrentUser.id(), sellerId);
    }
}
