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
     * The entitlement of one account, without hydrating the entity — the guard on every creation
     * path needs the plan and nothing else. Empty when the account is gone; the caller decides what
     * that means ({@code PlanLimitEnforcer} reads it as FREE).
     */
    @Query("select u.plan from User u where u.id = :userId")
    Optional<Plan> findPlanById(@Param("userId") UUID userId);
}
