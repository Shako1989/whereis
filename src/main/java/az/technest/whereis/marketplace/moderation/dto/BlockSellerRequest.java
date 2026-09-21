package az.technest.whereis.marketplace.moderation.dto;

import az.technest.whereis.marketplace.SellerBlockReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Why an operator is barring a seller from the board.
 *
 * <p>{@code reason} is REQUIRED and is an enum, because it is shown to the seller and therefore has
 * to be translatable; {@code note} is the operator's own words for a colleague and is optional,
 * because a block whose reason is already {@code SCAM_OR_FRAUD} needs no essay. Together they are
 * what makes {@code blocked_sellers} an audit row rather than a row saying only that somebody did
 * something.
 *
 * @param reason one of {@link SellerBlockReason}; anything else is a 400 before the service runs
 * @param note   internal, never on any wire again — not the board, not the seller's own responses
 */
public record BlockSellerRequest(
        @NotNull SellerBlockReason reason,
        @Size(max = 500) String note) {
}
