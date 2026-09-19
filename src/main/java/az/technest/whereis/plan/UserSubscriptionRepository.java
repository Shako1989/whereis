package az.technest.whereis.plan;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, UUID> {

    /**
     * THE ENTITLEMENT RULE, in one place, in one query — the only finder any entitlement decision
     * may use. There is deliberately no second projection-only variant: the guard and the report
     * previously computed the tier through two different queries, and two copies of a five-predicate
     * WHERE clause diverge the first time somebody adds a predicate to the one they were looking at.
     * Rows per account are a handful, so returning them costs nothing worth a second query.
     *
     * <p>The four predicates:
     * <ul>
     *   <li>{@code entitledUntil > now} — the fail-closed guard for a missed expiry notification.
     *       Even if every RTDN is lost, entitlement lapses on its own.</li>
     *   <li>state in ACTIVE / IN_GRACE_PERIOD / CANCELED. CANCELED means auto-renew is off but the
     *       paid term is not over. PAUSED and ON_HOLD do NOT entitle, even with a future expiry.
     *       The list comes from {@link SubscriptionState#ENTITLING_STATES_JPQL} so it is written
     *       once; {@code SubscriptionStateTest} pins it against {@link SubscriptionState#entitles()}.</li>
     *   <li>{@code voidedAt is null} — a refund revokes immediately, not at expiry.</li>
     *   <li>{@code supersededBy is null} — the replaced half of an upgrade stops counting.</li>
     * </ul>
     *
     * <p>DO NOT add {@code order by s.tier} and take the first row: {@code @Enumerated(STRING)}
     * orders alphabetically and 'MAX' sorts before 'PRO' and 'STANDARD', which is the ladder upside
     * down. Reduce in Java with {@link Plan#higherOf}.
     */
    @Query("""
            select s from UserSubscription s
             where s.userId = :userId
               and s.entitledUntil > :now
               and s.voidedAt is null
               and s.supersededBy is null
               and s.state in (""" + SubscriptionState.ENTITLING_STATES_JPQL + ")")
    List<UserSubscription> entitlingOf(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * <strong>Deliberately NOT userId-scoped, and the only such finder in the codebase.</strong>
     * The purchase token is a globally unique key issued by Google, and the entire purpose of this
     * lookup is to discover that a token already belongs to SOMEBODY ELSE — a scoped finder would
     * answer "not found" and the endpoint would happily bind the same purchase to a second account.
     * Every caller must compare {@code row.getUserId()} against the JWT subject and answer 409
     * {@code PLAN_PURCHASE_NOT_OWNED} on a mismatch. {@code ..whereis.plan..} is for that reason
     * NOT in the scoped-finder ArchUnit rule's package list, and a separate rule keeps this
     * repository from being reachable outside {@code plan/}.
     */
    Optional<UserSubscription> findByPurchaseToken(String purchaseToken);
}
