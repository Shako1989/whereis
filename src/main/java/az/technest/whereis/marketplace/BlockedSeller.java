package az.technest.whereis.marketplace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One account barred from the public marketplace: the row whose EXISTENCE is the sanction.
 *
 * <p><strong>The user id is the primary key</strong>, so blocking a blocked seller updates one row
 * rather than creating a second that could later disagree, and "is this account blocked" has
 * exactly one answer with no ordering and no state machine. Unblocking is one DELETE, which is why
 * there is no {@code unblocked_at} and no half-cleared state to get wrong — V13 records why an
 * append-only ledger was designed and rejected.
 *
 * <p><strong>A marketplace sanction, not an account deletion.</strong> Nothing about this row
 * touches {@code items}, {@code spaces}, {@code locations} or {@code item_files}: the seller keeps
 * their whole private inventory, keeps reading it and keeps using the application. What they lose is
 * the publishing privilege — business rule 7's exception to business rule 2, withdrawn from one
 * account.
 *
 * <p>Not {@code AuditedEntity}: a block is decided once and {@code blocked_at} IS its timestamp, so
 * {@code updated_at} would be a second column stating the same fact. Same choice as
 * {@link ListingReport}.
 */
@Entity
@Table(name = "blocked_sellers")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class BlockedSeller {

    /** The blocked account. Assigned, never generated: it is somebody else's id. */
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * Updatable on purpose. Re-blocking an account after an unblock-then-reoffend is a NEW decision
     * and must carry its own time, its own reason and its own author — not the first one's.
     */
    @Column(name = "blocked_at", nullable = false)
    private Instant blockedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SellerBlockReason reason;

    /** The operator's own words, for a colleague. NEVER on any wire, public or seller-facing. */
    @Column(length = 500)
    private String note;

    /**
     * The moderator's e-mail, {@code Names.normalize}d — the form {@code users.email} is stored in
     * and the form the allowlist is compared in, because two normalizations would let the audit
     * column and the authorization check disagree about who acted.
     */
    @Column(name = "blocked_by", nullable = false, length = 320)
    private String blockedBy;
}
