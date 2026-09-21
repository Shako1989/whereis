package az.technest.whereis.marketplace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * One anonymous complaint about one listing. The report endpoint is the only unauthenticated WRITE
 * in this application, and this is its only INSERT target.
 *
 * <p><strong>Nothing is stored about the reporter</strong> — no IP, no hash of one, no fingerprint.
 * That would be the only column in this feature collecting a new category of data about somebody
 * who is not a user of this application, which means a new paragraph on {@code /legal/privacy} in
 * both languages and a matching Data-safety answer. The per-IP limit lives in memory
 * ({@code MarketBoardRateLimitFilter}) and stores nothing; the per-listing cap needs no identity.
 *
 * <p>Deliberately does NOT extend {@code AuditedEntity}: a report is written once and never
 * updated by its author, so {@code updated_at} would be a column nothing writes. Same choice as
 * {@code AssistantMessage}.
 */
@Entity
@Table(name = "listing_reports")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ListingReport {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "listing_id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private UUID listingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ListingReportReason reason;

    /** The reporter's own words: untrusted anonymous input an OPERATOR reads. Never rendered publicly. */
    @Column(length = 500)
    private String note;

    @Column(name = "reported_at", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant reportedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_outcome", length = 24)
    private ListingReportOutcome reviewOutcome;

    @PrePersist
    void onCreate() {
        this.reportedAt = Instant.now();
    }
}
