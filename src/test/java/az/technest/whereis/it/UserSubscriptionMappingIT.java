package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.PurchaseProvenance;
import az.technest.whereis.plan.SubscriptionState;
import az.technest.whereis.plan.UserSubscription;
import az.technest.whereis.plan.UserSubscriptionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V10 against a real PostgreSQL, through the entity.
 *
 * <p>Hibernate's {@code ddl-auto: validate} is a narrower net than it looks: it checks that the
 * columns of MAPPED entities exist with a compatible JDBC type, and it says nothing about a column
 * nobody mapped or a table with no entity at all. A full round trip of every column is what
 * actually proves the 18-column table is writable, and the constraint cases below are what prove
 * the invariants the next wave will depend on are really in the database and not only in a comment.
 */
class UserSubscriptionMappingIT extends AbstractIntegrationTest {

    @Autowired
    private UserSubscriptionRepository subscriptions;

    @Autowired
    private TransactionTemplate transactions;

    private UUID freshUser() {
        return subjectOf(registerAndGetToken());
    }

    @Test
    void everyColumnRoundTripsThroughTheEntity() {
        UUID userId = freshUser();
        Instant expiry = Instant.now().plus(Duration.ofDays(365));
        UUID id = transactions.execute(status -> subscriptions.save(UserSubscription.builder()
                .userId(userId)
                .purchaseToken("token-" + UUID.randomUUID())
                .productId("whereis_pro_annual")
                .tier(Plan.PRO)
                .provenance(PurchaseProvenance.PROMO_CODE)
                .state(SubscriptionState.IN_GRACE_PERIOD)
                .entitledUntil(expiry)
                .acknowledged(true)
                .linkedPurchaseToken("previous-token")
                .voidedAt(null)
                .testPurchase(true)
                .latestOrderId("GPA.1234-5678-9012-34567")
                .lastEventTime(1_758_283_440_000L)
                .verifiedAt(Instant.now())
                .build())).getId();

        UserSubscription reloaded = transactions.execute(status ->
                subscriptions.findById(id).orElseThrow());

        assertThat(reloaded.getUserId()).isEqualTo(userId);
        assertThat(reloaded.getTier()).isEqualTo(Plan.PRO);
        assertThat(reloaded.getProvenance()).isEqualTo(PurchaseProvenance.PROMO_CODE);
        assertThat(reloaded.getState()).isEqualTo(SubscriptionState.IN_GRACE_PERIOD);
        assertThat(reloaded.getEntitledUntil()).isCloseTo(expiry, within(1000));
        assertThat(reloaded.isAcknowledged()).isTrue();
        assertThat(reloaded.getLinkedPurchaseToken()).isEqualTo("previous-token");
        assertThat(reloaded.isTestPurchase()).isTrue();
        assertThat(reloaded.getLatestOrderId()).isEqualTo("GPA.1234-5678-9012-34567");
        assertThat(reloaded.getLastEventTime()).isEqualTo(1_758_283_440_000L);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    private static org.assertj.core.data.TemporalUnitOffset within(long millis) {
        return org.assertj.core.api.Assertions.within(millis, java.time.temporal.ChronoUnit.MILLIS);
    }

    @Test
    void theVerifyEndpointsRowShapeLeavesTheHighWaterMarkNull() {
        UUID userId = freshUser();

        UUID id = transactions.execute(status -> subscriptions.save(UserSubscription.builder()
                .userId(userId)
                .purchaseToken("token-" + UUID.randomUUID())
                .productId("whereis_standard_annual")
                .tier(Plan.STANDARD)
                .provenance(PurchaseProvenance.PLAY_PURCHASE)
                .state(SubscriptionState.ACTIVE)
                .entitledUntil(Instant.now().plus(Duration.ofDays(365)))
                .verifiedAt(Instant.now())
                .build())).getId();

        // NULL means "no notification has ever been applied to this row". Seeding it with now()
        // would put the mark ahead of every RTDN already in flight for this purchase.
        assertThat(jdbc.queryForObject("select last_event_time from user_subscriptions where id = ?",
                Long.class, id)).isNull();
    }

    @Test
    void onePurchaseTokenBelongsToOneRowForever() {
        UUID first = freshUser();
        UUID second = freshUser();
        String shared = "token-" + UUID.randomUUID();
        transactions.execute(status -> subscriptions.save(row(first, shared, Plan.PRO)));

        // The unique constraint IS the idempotency key, and it is also what makes a token
        // unbindable to a second account.
        assertThatThrownBy(() -> transactions.execute(status ->
                subscriptions.save(row(second, shared, Plan.PRO))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anUnlimitedTierOnASubscriptionIsRefusedByTheDatabaseItself() {
        UUID userId = freshUser();

        // Not a Java-level check: the CHECK is what guarantees "granted" stays distinguishable from
        // "paid" no matter what a future writer does.
        assertThatThrownBy(() -> jdbc.update(
                "insert into user_subscriptions (user_id, purchase_token, product_id, tier, provenance,"
                        + " state, entitled_until, verified_at)"
                        + " values (?, ?, 'whereis_pro_annual', 'UNLIMITED', 'PLAY_PURCHASE', 'ACTIVE',"
                        + " now() + interval '1 year', now())",
                userId, "token-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSupersessionChainMayNotCrossUsers() {
        UUID mine = freshUser();
        UUID theirs = freshUser();
        UUID theirRow = transactions.execute(status ->
                subscriptions.save(row(theirs, "token-" + UUID.randomUUID(), Plan.PRO))).getId();
        UUID myRow = transactions.execute(status ->
                subscriptions.save(row(mine, "token-" + UUID.randomUUID(), Plan.PRO))).getId();

        // linkedPurchaseToken is scoped to a PLAY account, not a whereis account, so the next
        // wave's resolver can find a row belonging to someone else. If that were storable, deleting
        // one of the two users would abort the whole single-transaction account deletion — which
        // the Play Store requires to work. The composite FK makes it a hard error here instead.
        assertThatThrownBy(() -> jdbc.update(
                "update user_subscriptions set superseded_by = ? where id = ?", theirRow, myRow))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRowMayBeSupersededAtMostOnceSoAChainCannotLoop() {
        UUID userId = freshUser();
        UUID target = transactions.execute(status ->
                subscriptions.save(row(userId, "token-" + UUID.randomUUID(), Plan.PRO))).getId();
        UUID firstReplacer = transactions.execute(status ->
                subscriptions.save(row(userId, "token-" + UUID.randomUUID(), Plan.PRO))).getId();
        UUID secondReplacer = transactions.execute(status ->
                subscriptions.save(row(userId, "token-" + UUID.randomUUID(), Plan.PRO))).getId();
        jdbc.update("update user_subscriptions set superseded_by = ? where id = ?", target, firstReplacer);

        assertThatThrownBy(() -> jdbc.update(
                "update user_subscriptions set superseded_by = ? where id = ?", target, secondReplacer))
                .isInstanceOf(DataIntegrityViolationException.class);
        // And a row can never supersede itself, which together with the above makes any cycle
        // unrepresentable — a cycle would strand a paying user reading FREE with nothing to explain it.
        assertThatThrownBy(() -> jdbc.update(
                "update user_subscriptions set superseded_by = id where id = ?", secondReplacer))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAnAccountTakesItsWholeSupersessionChainWithIt() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        UUID older = transactions.execute(status ->
                subscriptions.save(row(userId, "token-" + UUID.randomUUID(), Plan.PRO))).getId();
        UUID newer = transactions.execute(status ->
                subscriptions.save(row(userId, "token-" + UUID.randomUUID(), Plan.MAX))).getId();
        jdbc.update("update user_subscriptions set superseded_by = ? where id = ?", newer, older);

        // The SCHEMA half of the cascade, which V11 did not change: user_subscriptions hangs off
        // users with ON DELETE CASCADE, and the self-FK is NO ACTION (checked at end of statement),
        // so a whole same-user chain goes in one statement exactly like the location forest.
        //
        // (V11 DID add a step to AccountDeletionService — the Play cancellation outbox, enqueued
        // first — which is why this comment no longer says the service is untouched.
        // AccountDeletionIT owns that behaviour; this test owns the FK shape.)
        assertThat(deleteWithBody(token, "/api/v1/users/me",
                java.util.Map.of("password", PASSWORD), Void.class).getStatusCode().value())
                .isEqualTo(204);
        assertThat(jdbc.queryForObject("select count(*) from user_subscriptions where id in (?, ?)",
                Integer.class, older, newer)).isZero();
    }

    @Test
    void theEntitlingQueryAgreesWithTheEntityPredicateOnEveryShape() {
        UUID userId = freshUser();
        Instant now = Instant.now();
        Instant future = now.plus(Duration.ofDays(30));
        Instant past = now.minus(Duration.ofDays(1));

        UUID replaced = transactions.execute(status ->
                subscriptions.save(row(userId, "token-" + UUID.randomUUID(), Plan.PRO))).getId();
        for (SubscriptionState state : SubscriptionState.values()) {
            transactions.execute(status -> subscriptions.save(rowAt(userId, state, future, null, null)));
            transactions.execute(status -> subscriptions.save(rowAt(userId, state, past, null, null)));
        }
        transactions.execute(status -> subscriptions.save(
                rowAt(userId, SubscriptionState.ACTIVE, future, past, null)));
        transactions.execute(status -> subscriptions.save(
                rowAt(userId, SubscriptionState.ACTIVE, future, null, replaced)));

        Instant asOf = Instant.now();
        List<UserSubscription> fromSql = subscriptions.entitlingOf(userId, asOf);
        List<UserSubscription> all = transactions.execute(status -> subscriptions.findAll().stream()
                .filter(row -> userId.equals(row.getUserId()))
                .toList());

        // THE DRIFT GUARD THAT MATTERS: the four predicates exist as JPQL in the repository and as
        // Java in UserSubscription#entitlesAt. If they ever disagree, a purchase verifies 200 and
        // then reads as FREE.
        assertThat(fromSql.stream().map(UserSubscription::getId).sorted().toList())
                .isEqualTo(all.stream().filter(row -> row.entitlesAt(asOf))
                        .map(UserSubscription::getId).sorted().toList());
        assertThat(fromSql).isNotEmpty();
    }

    private static UserSubscription row(UUID userId, String token, Plan tier) {
        return UserSubscription.builder()
                .userId(userId)
                .purchaseToken(token)
                .productId("whereis_pro_annual")
                .tier(tier)
                .provenance(PurchaseProvenance.PLAY_PURCHASE)
                .state(SubscriptionState.ACTIVE)
                .entitledUntil(Instant.now().plus(Duration.ofDays(365)))
                .verifiedAt(Instant.now())
                .build();
    }

    private static UserSubscription rowAt(UUID userId, SubscriptionState state, Instant entitledUntil,
                                          Instant voidedAt, UUID supersededBy) {
        UserSubscription row = row(userId, "token-" + UUID.randomUUID(), Plan.STANDARD);
        row.setState(state);
        row.setEntitledUntil(entitledUntil);
        row.setVoidedAt(voidedAt);
        row.setSupersededBy(supersededBy);
        return row;
    }
}
