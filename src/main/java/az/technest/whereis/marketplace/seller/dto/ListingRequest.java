package az.technest.whereis.marketplace.seller.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a seller publishes, and what they may edit afterwards — the same record for both, because
 * the edit path re-runs the identical validation.
 *
 * <p><strong>The title and description are typed, never defaulted from the item.</strong>
 * {@code items.description} is a PRIVATE note the user may have been writing for a year, and
 * promoting it to public copy without the user seeing it is the same class of mistake as
 * publishing the location path. {@code @NotBlank} on the title is what structurally forces the
 * client to show what is about to become public.
 *
 * @param title        the public headline
 * @param description  the selling text; a MINIMUM length is enforced in the service, on the
 *                     cleaned value and in code points, because bean validation runs before
 *                     cleaning and counts UTF-16 units
 * @param price        in AZN. {@code @Digits} is what stops PostgreSQL silently rounding 10.999 to
 *                     11.00 and pricing the listing differently from what the seller typed
 * @param contactPhone published to anonymous visitors; normalised before it is stored
 * @param city         where a buyer can collect it — a seller STATEMENT, never derived from the
 *                     item's location, because deriving it would publish the space name
 * @param coverFileId  one of this item's own photos, or null for the primary-else-oldest cover
 */
public record ListingRequest(
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Size(max = 4000) String description,
        @NotNull @DecimalMin("0.01") @DecimalMax("10000000.00") @Digits(integer = 8, fraction = 2)
        BigDecimal price,
        @NotBlank @Size(max = 32) String contactPhone,
        @NotBlank @Size(max = 80) String city,
        UUID coverFileId) {
}
