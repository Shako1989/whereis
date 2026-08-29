package az.technest.whereis.item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
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
@Table(name = "item_location_history")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ItemLocationHistory {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    /** Nullable: history survives location deletion (ON DELETE SET NULL); the snapshot keeps it readable. */
    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "location_path_snapshot", nullable = false, columnDefinition = "text")
    private String locationPathSnapshot;

    @Column(length = 500)
    private String note;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "removed_at")
    private Instant removedAt;
}
