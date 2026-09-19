package az.technest.whereis.plan;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * THE SCOPED counterpart, and V10's hard contract for resolving {@code linkedPurchaseToken}.
     *
     * <p>{@code superseded_by} has a COMPOSITE self-FK to {@code (id, user_id)} because one Play
     * account can be signed into two whereis accounts. Resolving an upgrade's link with the global
     * {@link #findByPurchaseToken} and writing it would produce a cross-user chain, a
     * {@code DataIntegrityViolationException}, and — worse — a {@code user_subscriptions} row that
     * the Play-mandated {@code DELETE /users/me} cannot get past, turning account deletion into a
     * 500. This is not a style preference; see {@code SubscriptionLinkResolver}.
     */
    Optional<UserSubscription> findByUserIdAndPurchaseToken(UUID userId, String purchaseToken);

    /**
     * The row, locked for the duration of the transaction ({@code SELECT … FOR UPDATE}, the
     * {@code moveItem} pattern).
     *
     * <p>Every guarded write goes through this rather than {@code findById}, so the guard and the
     * write are ATOMIC instead of check-then-act. Without the lock, a reconcile that read the row
     * before a concurrent RTDN revoke would simply wait for the revoke to commit and then write
     * {@code voided_at = NULL} back from its own stale snapshot — a chargeback silently undone.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserSubscription s where s.id = :id")
    Optional<UserSubscription> findForUpdate(@Param("id") UUID id);

    /**
     * A LIVE Play purchase the user could still act on — what {@code PlanStatusResponse.subscription}
     * reports, which is a strictly wider question than "what entitles them".
     *
     * <p>The difference is the whole point of the field. An {@code ON_HOLD} subscriber is not
     * entitled — their badge reads FREE — and must still see a strip saying their payment failed
     * and a button that opens Google Play. Reporting {@code null} for them, as wave 1 did, is how a
     * paying customer loses the only control that can fix their subscription.
     *
     * <p>The predicate, and why each half is there:
     * <ul>
     *   <li>{@code purchase_token IS NOT NULL} — an OPERATOR grant has nothing to manage in Play;</li>
     *   <li>{@code voided_at IS NULL} — a refund leaves nothing to manage and nothing to fix;</li>
     *   <li>{@code superseded_by IS NULL} — the replaced half of an upgrade is not the live one;</li>
     *   <li>{@code state NOT IN (EXPIRED, PENDING_PURCHASE_CANCELED)} — WITHOUT this, a
     *       subscription that lapsed a year ago would leave a permanent, actionless "Manage
     *       subscription" button on a FREE account whose strip the client hides.</li>
     * </ul>
     *
     * <p><strong>This finder can never influence the TIER.</strong> {@code plan}, {@code limits} and
     * {@code usage} still come from {@link #entitlingOf} alone — {@code PlanLimitEnforcerTest} pins
     * that, because an entitling STANDARD row beside a live ON_HOLD MAX row must badge STANDARD
     * while the strip talks about MAX, and a single reduction over both would make the badge lie.
     * This is the first crack in "one finder" and the next person to add a query here should be
     * made to justify it.
     */
    @Query("select s from UserSubscription s"
            + " where s.userId = :userId"
            + "   and s.purchaseToken is not null"
            + "   and s.voidedAt is null"
            + "   and s.supersededBy is null"
            + "   and s.state <> az.technest.whereis.plan.SubscriptionState.EXPIRED"
            + "   and s.state <> az.technest.whereis.plan.SubscriptionState.PENDING_PURCHASE_CANCELED")
    List<UserSubscription> manageableOf(@Param("userId") UUID userId);

    /**
     * THE RECONCILER'S ONE HOT QUERY: the live rows Google has not confirmed recently, oldest
     * first, with anything still unacknowledged ahead of them. Served by
     * {@code ix_user_subscriptions_reconcile} — {@code (acknowledged, verified_at)} partial, whose
     * leading column matches the leading sort key. (An index on {@code (verified_at)} alone, which
     * the first draft of V11 proposed, could not supply this ordering at all.)
     *
     * <p>Every interval is computed in Java and passed as a parameter, because JPQL has no interval
     * arithmetic. The three decisions inside the predicate:
     * <ul>
     *   <li><strong>unacknowledged rows come first and ignore the staleness clock.</strong> Google
     *       auto-refunds an unacknowledged purchase after 3 days — 5 MINUTES for a test purchase,
     *       i.e. every purchase on the closed track. This is what finally closes the wave-1 gap
     *       where one transient 5xx during {@code acknowledge} left an account this database says
     *       is entitled for a year and Google has silently refunded;</li>
     *   <li><strong>35 days of graveyard.</strong> An expired row can still come back — ON_HOLD
     *       lasts up to 30 days and ends in {@code SUBSCRIPTION_RECOVERED} — but nothing comes back
     *       after that, and sweeping two-year-old rows forever is quota spent on nothing;</li>
     *   <li><strong>{@code purchase_token is not null}</strong> excludes OPERATOR grants. There is
     *       nothing at Google to reconcile them against.</li>
     * </ul>
     */
    @Query("select s from UserSubscription s"
            + " where s.voidedAt is null"
            + "   and s.supersededBy is null"
            + "   and s.purchaseToken is not null"
            + "   and s.entitledUntil > :graveyardBefore"
            + "   and ( (s.acknowledged = false and s.createdAt > :ackCutoff)"
            + "         or s.verifiedAt < :staleBefore )"
            + " order by s.acknowledged asc, s.verifiedAt asc")
    List<UserSubscription> reconcileCandidates(@Param("graveyardBefore") Instant graveyardBefore,
                                               @Param("ackCutoff") Instant ackCutoff,
                                               @Param("staleBefore") Instant staleBefore,
                                               Pageable page);
}
