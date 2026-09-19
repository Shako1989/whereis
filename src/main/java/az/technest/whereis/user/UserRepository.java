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
}
