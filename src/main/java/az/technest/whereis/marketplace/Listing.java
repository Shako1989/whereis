package az.technest.whereis.marketplace;

import az.technest.whereis.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UuidGenerator;

/**
 * One offer a user published from an item they already own. Every field on this row is either
 * readable by anyone on the internet or exists to stop something being readable.
 *
 * <p><strong>A listing is a SNAPSHOT, not a view of an item.</strong> It carries its own title,
 * description, price, city and phone; nothing is derived from {@code items} at read time. That is
 * what makes "the internal location path can never be published" structural rather than a habit —
 * the board's query has no location id to resolve. It is also what stops a routine
 * {@code PUT /items/{id}} from blanking a live listing's public text: {@code ItemService.update}
 * applies {@code description} unconditionally, so a request omitting the field clears it.
 *
 * <p><strong>{@code @DynamicUpdate} is load-bearing here, exactly as it is on
 * {@code UserSubscription}.</strong> The operator's kill-switch is a raw SQL {@code UPDATE} (this
 * codebase has no admin API — grants are SQL too), so a seller action that loaded the row before
 * the hide and writes after it would emit a FULL-COLUMN update and write {@code hidden_at = NULL}
 * back from a stale snapshot, silently un-hiding a listing an operator had just killed.
 *
 * <p>{@code userId} and {@code itemId} are write-once: a listing can never be re-pointed at
 * another owner or another item. Re-listing is a new row, not a mutation.
 */
@Entity
@Table(name = "listings")
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Listing extends AuditedEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID userId;

    @Column(name = "item_id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ListingStatus status;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "hidden_at")
    private Instant hiddenAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "hidden_reason", length = 32)
    private ListingHiddenReason hiddenReason;

    @Column(name = "hidden_note", length = 500)
    private String hiddenNote;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    /**
     * No {@code precision} and no {@code scale} on purpose — {@code assistant_messages.confidence}'s
     * lesson. A value here that disagrees with {@code numeric(12,2)} stops the application booting
     * under {@code ddl-auto: validate}.
     */
    @Column(name = "price_amount", nullable = false)
    private BigDecimal priceAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_currency", nullable = false, length = 3)
    private ListingCurrency priceCurrency;

    @Column(name = "contact_phone", nullable = false, length = 32)
    private String contactPhone;

    @Column(nullable = false, length = 80)
    private String city;

    @Column(name = "normalized_city", nullable = false, length = 80)
    private String normalizedCity;

    @Column(name = "cover_file_id", nullable = false)
    private UUID coverFileId;

    /**
     * <strong>This row's half of the visibility rule</strong> — and since V13 it is only a half.
     * The other half is the SELLER's standing, which no column of this table carries: a blocked
     * seller's listing stays {@code ACTIVE} with {@code hidden_at} NULL and is still off the board,
     * because the board excludes the account rather than mutating its rows. The whole rule lives in
     * {@code MarketBoardDao.VISIBLE}, where {@code MarketBoardVisibilityTest} pins all three
     * clauses against the SQL both public queries actually run.
     *
     * <p>Drift between the two does not fail a build or a request: it publishes a withdrawn listing
     * or hides every live one, silently.
     */
    public boolean isPubliclyVisible() {
        return status.isPubliclyVisible() && hiddenAt == null;
    }

    /** True while an operator has this listing off the board, whatever the seller has decided. */
    public boolean isHidden() {
        return hiddenAt != null;
    }
}
