package az.technest.whereis.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(name = "item_files")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ItemFile {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(nullable = false, length = 100)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;

    /**
     * The key of the PUBLIC copy of this photo, or null while it has none.
     *
     * <p>The private key is {@code u/{userId}/i/{itemId}/{fileId}} and a presigned URL carries the
     * key in its PATH, so serving the private object to the anonymous marketplace board would
     * publish the seller's user UUID and the item's UUID to every visitor and every crawler — a
     * stable correlation key that would let a scraper cluster every listing to one person, which is
     * exactly what leaving sellerId out of the public DTO is meant to prevent. Publishing copies
     * the object server-side to an opaque {@code p/{uuid}} key instead, and this records it.
     */
    @Column(name = "published_object_key", columnDefinition = "text")
    private String publishedObjectKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
