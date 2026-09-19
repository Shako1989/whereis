package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two write paths, and the ownership check that belongs to both of them.
 *
 * <p>Both cases below were found by {@code PlanPurchaseIT}'s two-account race rather than by
 * reasoning: the orchestrator's ownership check runs BEFORE the Google round trip, so by the time a
 * write happens the row may have been created by somebody else — either as an existing row this
 * method would have updated, or as the winner of an INSERT race.
 */
class SubscriptionWriterTest {

    private final UUID caller = UUID.randomUUID();
    private final UUID otherUser = UUID.randomUUID();

    private UserSubscriptionRepository subscriptions;
    private SubscriptionWriter writer;

    @BeforeEach
    void setUp() {
        subscriptions = mock(UserSubscriptionRepository.class);
        writer = new SubscriptionWriter(subscriptions);
        when(subscriptions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private SubscriptionWriter.Snapshot snapshot() {
        return new SubscriptionWriter.Snapshot(caller, "token", "whereis_pro_annual", Plan.PRO,
                PurchaseProvenance.PLAY_PURCHASE, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)), true, null, false, "GPA.1", Instant.now(), null);
    }

    private UserSubscription existing(UUID owner) {
        return UserSubscription.builder()
                .id(UUID.randomUUID())
                .userId(owner)
                .purchaseToken("token")
                .productId("whereis_standard_annual")
                .tier(Plan.STANDARD)
                .provenance(PurchaseProvenance.PLAY_PURCHASE)
                .state(SubscriptionState.PENDING)
                .entitledUntil(Instant.now())
                .verifiedAt(Instant.now().minus(Duration.ofHours(2)))
                .build();
    }

    @Test
    void anUnknownTokenIsInserted() {
        when(subscriptions.findByPurchaseToken("token")).thenReturn(Optional.empty());

        UserSubscription written = writer.upsert(snapshot());

        assertThat(written.getUserId()).isEqualTo(caller);
        assertThat(written.getTier()).isEqualTo(Plan.PRO);
        verify(subscriptions).save(any());
    }

    @Test
    void theCallersOwnRowIsRefreshedInPlace() {
        UserSubscription mine = existing(caller);
        when(subscriptions.findByPurchaseToken("token")).thenReturn(Optional.of(mine));

        UserSubscription written = writer.upsert(snapshot());

        assertThat(written).isSameAs(mine);
        assertThat(written.getTier()).isEqualTo(Plan.PRO);
        assertThat(written.getState()).isEqualTo(SubscriptionState.ACTIVE);
        // No second row: the token already names this one.
        verify(subscriptions, never()).save(any());
    }

    @Test
    void anotherAccountsRowIsNeverUpdatedEvenThoughUserIdCouldNotChange() {
        UserSubscription theirs = existing(otherUser);
        when(subscriptions.findByPurchaseToken("token")).thenReturn(Optional.of(theirs));

        assertThatThrownBy(() -> writer.upsert(snapshot()))
                .isInstanceOf(PlanPurchaseNotOwnedException.class);

        // Nothing escalates (user_id is updatable = false), but answering 200 while linking nothing
        // is worse than refusing: the client marks the token sent and stops.
        assertThat(theirs.getTier()).isEqualTo(Plan.STANDARD);
        assertThat(theirs.getState()).isEqualTo(SubscriptionState.PENDING);
    }

    @Test
    void theRaceRepairRefusesAWinningRowThatBelongsToSomebodyElse() {
        when(subscriptions.findByPurchaseToken("token")).thenReturn(Optional.of(existing(otherUser)));

        assertThatThrownBy(() -> writer.refreshOwned(snapshot(), caller))
                .isInstanceOf(PlanPurchaseNotOwnedException.class);
    }

    @Test
    void theRaceRepairAppliesTheSnapshotWhenTheWinnerIsTheCaller() {
        UserSubscription mine = existing(caller);
        when(subscriptions.findByPurchaseToken("token")).thenReturn(Optional.of(mine));

        assertThat(writer.refreshOwned(snapshot(), caller).getTier()).isEqualTo(Plan.PRO);
    }

    @Test
    void markAcknowledgedIsAQuietNoOpForARowThatIsGone() {
        when(subscriptions.findById(any())).thenReturn(Optional.empty());

        writer.markAcknowledged(UUID.randomUUID());
    }
}
