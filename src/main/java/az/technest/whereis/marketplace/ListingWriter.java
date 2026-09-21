package az.technest.whereis.marketplace;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The INSERT, in its own short transaction, reached through the Spring proxy.
 *
 * <p>It is a separate bean rather than a {@code @Transactional} method on {@link ListingService}
 * because {@code publish} must NOT be transactional — it copies an object in MinIO, and no
 * database transaction may span an external call. A private or protected method on the service
 * would be self-invocation: the proxy is bypassed and the annotation does nothing, silently. Same
 * shape as {@code ItemFilePersister} and {@code SubscriptionWriter}, for the same reason.
 */
@Component
@RequiredArgsConstructor
public class ListingWriter {

    private final ListingRepository listingRepository;

    @Transactional
    public Listing insert(UUID userId, UUID itemId, UUID coverFileId, String title,
            String description, BigDecimal price, String phone, String city, String normalizedCity) {
        return listingRepository.save(Listing.builder()
                .userId(userId)
                .itemId(itemId)
                .status(ListingStatus.ACTIVE)
                .title(title)
                .description(description)
                .priceAmount(price)
                .priceCurrency(ListingCurrency.AZN)
                .contactPhone(phone)
                .city(city)
                .normalizedCity(normalizedCity)
                .coverFileId(coverFileId)
                .build());
    }
}
