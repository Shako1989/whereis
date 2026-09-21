package az.technest.whereis.user;

import az.technest.whereis.plan.Plan;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * The OPERATOR GRANT on one account, without hydrating the entity — one half of what the guard
     * on every creation path needs (the other is the account's entitling subscriptions). Empty when
     * the account is gone; the caller decides what that means ({@code PlanLimitEnforcer} reads it
     * as FREE, because a guard's default must be the restrictive one).
     */
    @Query("select u.plan from User u where u.id = :userId")
    Optional<Plan> findPlanById(@Param("userId") UUID userId);

    /**
     * One account's e-mail, without hydrating the entity — the identity an OPERATOR is configured
     * by. {@code SellerBlockService} uses it twice: to decide whether the caller is on the
     * moderator allowlist (the stored value is already {@code Names.normalize}d, which is the form
     * the allowlist is compared in) and to turn a mistyped seller id into a 404 rather than the
     * foreign key's 409.
     *
     * <p>Empty means the account is gone. Every caller must read that as a refusal: a guard's
     * default is the restrictive one, exactly as {@link #findPlanById(UUID)} is read as FREE.
     */
    @Query("select u.email from User u where u.id = :userId")
    Optional<String> findEmailById(@Param("userId") UUID userId);
}
