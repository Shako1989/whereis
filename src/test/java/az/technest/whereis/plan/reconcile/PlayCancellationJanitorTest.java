package az.technest.whereis.plan.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import az.technest.whereis.common.legal.LegalProperties;
import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.PlanCatalog;
import az.technest.whereis.plan.PlayCancellationQueueEntry;
import az.technest.whereis.plan.PlayCancellationQueueRepository;
import az.technest.whereis.plan.PurchaseProvenance;
import az.technest.whereis.plan.SubscriptionState;
import az.technest.whereis.plan.UserSubscription;
import az.technest.whereis.plan.UserSubscriptionRepository;
import az.technest.whereis.plan.play.DisabledPlaySubscriptionsApi;
import az.technest.whereis.plan.play.FakePlaySubscriptionsApi;
import az.technest.whereis.plan.play.PlayProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * Draining the cancellation outbox. Three of these four cases are corrections a review forced, and
 * each of them is about money or about cancelling somebody who did not ask.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlayCancellationJanitorTest {

    private PlayCancellationQueueRepository queue;
    private UserSubscriptionRepository subscriptions;
    private FakePlaySubscriptionsApi play;
    private PlayCancellationJanitor janitor;

    @BeforeEach
    void setUp() {
        queue = mock(PlayCancellationQueueRepository.class);
        subscriptions = mock(UserSubscriptionRepository.class);
        play = new FakePlaySubscriptionsApi(catalog());
        janitor = new PlayCancellationJanitor(queue, subscriptions, play, properties(true),
                playProperties(PlayProperties.FAKE), legal("7"));
        when(subscriptions.findByPurchaseToken(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void theSweepDoesNothingAtAllWhenItsFlagIsOff() {
        PlayCancellationJanitor off = new PlayCancellationJanitor(queue, subscriptions, play,
                properties(false), playProperties(PlayProperties.FAKE), legal("7"));

        off.sweep();

        verify(queue, never()).findDue(any(), any());
    }

    @Test
    void aDueRowIsCancelledAtGoogleAndRemoved() {
        PlayCancellationQueueEntry entry = entry("fake-active-pro", Instant.now());
        given(entry);

        janitor.sweep();

        assertThat(play.cancelledTokens()).containsEntry("fake-active-pro", "whereis_pro_annual");
        verify(queue).deleteById(entry.getId());
    }

    @Test
    void aTokenThatBelongsToALiveAccountAgainIsNotCancelled() {
        // THE CASE NOTHING ELSE WOULD NOTICE. The queue row carries no user and no FK by design, so
        // after the person re-registers — which the legal page explicitly invites — wave 1's
        // PurchaseSyncer posts the same token on the FIRST foreground (cancel only turns auto-renew
        // off, so queryPurchasesAsync keeps returning it for the rest of the paid term). Cancelling
        // then turns auto-renew off for somebody who did not ask, with no notification anywhere.
        PlayCancellationQueueEntry entry = entry("fake-active-pro", Instant.now());
        given(entry);
        when(subscriptions.findByPurchaseToken("fake-active-pro")).thenReturn(Optional.of(liveRow()));

        janitor.sweep();

        assertThat(play.cancelledTokens()).isEmpty();
        verify(queue).deleteById(entry.getId());
    }

    @Test
    void aFourOhFourRemovesTheRowBecauseThereIsNothingLeftToCancel() {
        PlayCancellationQueueEntry entry = entry("not-a-fake-token", Instant.now());
        given(entry);

        janitor.sweep();

        verify(queue).deleteById(entry.getId());
        verify(queue, never()).save(any());
    }

    @Test
    void aRetryableFailureBacksOffRatherThanDiscardingTheCancellation() {
        // NOT deleted, and that is the correction. purchases.subscriptions.cancel is the v1 endpoint
        // and takes OUR stored product id, which can legitimately disagree with the token's current
        // product (a re-pointed whereis.plans.*.product-id, or a multi-line-item purchase whose
        // highest tier we deliberately stored). Discarding the row on such a failure would leave
        // Google auto-renewing a subscription whose account no longer exists, indefinitely.
        PlayCancellationQueueEntry entry = entry("fake-cancelfails-pro", Instant.now());
        given(entry);

        janitor.sweep();

        verify(queue, never()).deleteById(any());
        verify(queue).save(entry);
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(entry.getLastError()).isNotBlank();
    }

    @Test
    void aRowOlderThanTheNumberThePublicPageStatesIsGivenUpOn() {
        // The deadline is whereis.legal.cancellation-retry-days — the SAME property the public
        // account-deletion page is rendered from, in both languages. Binding both to one value is
        // what stops the page from lying, exactly as WHEREIS_LEGAL_BACKUP_RETENTION_DAYS does for
        // the backup script.
        PlayCancellationQueueEntry entry = entry("fake-active-pro", Instant.now().minus(Duration.ofDays(8)));
        given(entry);

        janitor.sweep();

        assertThat(play.cancelledTokens()).isEmpty();
        verify(queue).deleteById(entry.getId());
    }

    @Test
    void theDeadlineFollowsTheConfiguredNumberRatherThanAHardcodedSeven() {
        PlayCancellationJanitor thirty = new PlayCancellationJanitor(queue, subscriptions, play,
                properties(true), playProperties(PlayProperties.FAKE), legal("30"));
        PlayCancellationQueueEntry entry = entry("fake-active-pro", Instant.now().minus(Duration.ofDays(8)));
        given(entry);

        thirty.sweep();

        assertThat(play.cancelledTokens()).containsKey("fake-active-pro");
    }

    private void given(PlayCancellationQueueEntry entry) {
        when(queue.findDue(any(), any(Pageable.class))).thenReturn(List.of(entry));
    }

    private static PlayCancellationQueueEntry entry(String token, Instant createdAt) {
        PlayCancellationQueueEntry entry = PlayCancellationQueueEntry.builder()
                .id(UUID.randomUUID())
                .purchaseToken(token)
                .productId("whereis_pro_annual")
                .reason("ACCOUNT_DELETED")
                .attempts(0)
                .nextAttemptAt(createdAt)
                .build();
        entry.setCreatedAt(createdAt);
        return entry;
    }

    private static UserSubscription liveRow() {
        return UserSubscription.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .purchaseToken("fake-active-pro")
                .productId("whereis_pro_annual")
                .tier(Plan.PRO)
                .provenance(PurchaseProvenance.PLAY_PURCHASE)
                .state(SubscriptionState.ACTIVE)
                .entitledUntil(Instant.now().plus(Duration.ofDays(300)))
                .verifiedAt(Instant.now())
                .build();
    }

    private static LegalProperties legal(String cancellationRetryDays) {
        return new LegalProperties("a@b.c", "Entity", "Address", "2026-01-01", "14",
                cancellationRetryDays, "30");
    }

    /**
     * The billing-not-configured gate, with the {@code enabled} flag left ON. This is the job whose
     * queue keeps filling regardless — {@code AccountDeletionService} enqueues a cancellation
     * whether or not billing is configured — so the rows simply wait for billing to be switched on.
     */
    @Test
    void theScheduledSweepDoesNothingWhenBillingIsNotConfiguredEvenWithTheFlagOn() {
        PlayCancellationJanitor off = new PlayCancellationJanitor(queue, subscriptions,
                new DisabledPlaySubscriptionsApi(), properties(true),
                playProperties(PlayProperties.DISABLED), legal("7"));

        off.sweep();

        verify(queue, never()).findDue(any(), any());
    }

    private static PlayProperties playProperties(String provider) {
        return new PlayProperties(provider, "az.technest.whereis", null, Duration.ofSeconds(10));
    }

    private static ReconcileProperties properties(boolean enabled) {
        return new ReconcileProperties(null, null,
                new ReconcileProperties.Cancellation(enabled, 20), null);
    }

    private static PlanCatalog catalog() {
        Map<Plan, PlanCatalog.TierConfig> tiers = new EnumMap<>(Plan.class);
        tiers.put(Plan.FREE, new PlanCatalog.TierConfig(1, 100, null));
        tiers.put(Plan.STANDARD, new PlanCatalog.TierConfig(3, 300, "whereis_standard_annual"));
        tiers.put(Plan.PRO, new PlanCatalog.TierConfig(5, 600, "whereis_pro_annual"));
        tiers.put(Plan.MAX, new PlanCatalog.TierConfig(10, null, "whereis_max_annual"));
        tiers.put(Plan.UNLIMITED, new PlanCatalog.TierConfig(null, null, null));
        return new PlanCatalog(tiers);
    }
}
