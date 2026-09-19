package az.technest.whereis.user;

import az.technest.whereis.common.persistence.AuditedEntity;
import az.technest.whereis.plan.Plan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class User extends AuditedEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    /**
     * What the account is entitled to. Defaults to {@link Plan#FREE} for every new account, which
     * is also what V9 gave every existing row.
     *
     * <p>Deliberately has NO setter: this column is written by the migration's default or by an
     * operator's UPDATE, and by nothing in the application. When billing arrives, a subscription
     * expiry must not be able to overwrite a hand-made UNLIMITED grant — see
     * {@code PlanLimitEnforcer#hasUnlimitedEntitlement}, which is the one place the two will be
     * combined.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Setter(AccessLevel.NONE)
    @Builder.Default
    private Plan plan = Plan.FREE;
}
