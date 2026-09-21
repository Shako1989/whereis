package az.technest.whereis.plan.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.PlanCatalog;
import az.technest.whereis.plan.PurchaseProvenance;
import az.technest.whereis.plan.SubscriptionLinkResolver;
import az.technest.whereis.plan.SubscriptionSnapshots;
import az.technest.whereis.plan.SubscriptionState;
import az.technest.whereis.plan.SubscriptionWriter;
import az.technest.whereis.plan.UserSubscription;
import az.technest.whereis.plan.UserSubscriptionRepository;
import az.technest.whereis.plan.play.DisabledPlaySubscriptionsApi;
import az.technest.whereis.plan.play.FakePlaySubscriptionsApi;
import az.technest.whereis.plan.play.PlayProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/**
 * The reconciler's per-row error handling, which is where every one of its interesting decisions
 * lives: what a transport failure does, what a definitive Google refusal does, and — the one a
 * review had to add — that NOTHING can stop a batch.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SubscriptionReconcilerTest {

    private UserSubscriptionRepository subscriptions;
    private SubscriptionWriter writer;
    private SubscriptionReconciler reconciler;

    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        subscriptions = mock(UserSubscriptionRepository.class);
        writer = mock(SubscriptionWriter.class);
        PlanCatalog catalog = catalog();
        reconciler = new SubscriptionReconciler(subscriptions, writer, new SubscriptionSnapshots(catalog),
                new SubscriptionLinkResolver(subscriptions, writer),
                new FakePlaySubscriptionsApi(catalog), properties(true), playProperties(PlayProperties.FAKE));
        when(writer.reconcile(any(), any(), any())).thenReturn(true);
        when(writer.find(any())).thenReturn(Optional.empty());
    }

    @Test
    void theSweepDoesNothingAtAllWhenItsFlagIsOff() {
        // The single-instance mitigation: the operator's answer to "we are now running two" is to
        // turn the sweeps off on all but one.
        SubscriptionReconciler off = new SubscriptionReconciler(subscriptions, writer,
                new SubscriptionSnapshots(catalog()), new SubscriptionLinkResolver(subscriptions, writer),
                new FakePlaySubscriptionsApi(catalog()), properties(false), playProperties(PlayProperties.FAKE));

        off.sweep();

        verify(subscriptions, never()).reconcileCandidates(any(), any(), any(), any());
    }

    @Test
    void aFreshAnswerFromGoogleIsWrittenThroughTheCompareAndSetOnVerifiedAt() {
        UserSubscription row = row("fake-canceled-pro", SubscriptionState.ACTIVE, true);
        givenCandidates(row);

        reconciler.sweep();

        ArgumentCaptor<SubscriptionWriter.Snapshot> captor =
                ArgumentCaptor.forClass(SubscriptionWriter.Snapshot.class);
        verify(writer).reconcile(eq(row.getId()), captor.capture(), eq(row.getVerifiedAt()));
        assertThat(captor.getValue().state()).isEqualTo(SubscriptionState.CANCELED);
    }

    @Test
    void aTransportFailureLeavesTheRowEntirelyAloneSoItStaysAtTheHeadOfTheQueue() {
        // verified_at must NOT move: the row is retried in fifteen minutes, which is the whole
        // point of not bumping it.
        UserSubscription row = row("fake-outage-pro", SubscriptionState.ACTIVE, true);
        givenCandidates(row);

        reconciler.sweep();

        verify(writer, never()).reconcile(any(), any(), any());
        verify(writer, never()).touchVerifiedAt(any(), any());
    }

    @Test
    void aDefinitiveGoogleRefusalBumpsVerifiedAtAndRevokesNothing() {
        // GooglePlaySubscriptionsApi maps 400 as well as 404 here, and a 400 can be OUR bug.
        // Letting a Google error remove a paid entitlement is a far worse failure than leaving a
        // bogus row entitling — the fail-closed entitled_until predicate ends it on its own.
        //
        // The bump is still required: without it the row sits at the head of every batch forever
        // and starves the sweep.
        UserSubscription row = row("not-a-fake-token", SubscriptionState.ACTIVE, true);
        givenCandidates(row);

        reconciler.sweep();

        verify(writer).touchVerifiedAt(eq(row.getId()), any());
        verify(writer, never()).markVoided(any(), any(), any(), any());
        verify(writer, never()).reconcile(any(), any(), any());
    }

    @Test
    void oneBadRowCannotStopTheBatch() {
        // Without the outer catch, a row whose Google answer throws anything unforeseen aborts the
        // WHOLE batch — and because verified_at was never bumped it is the oldest candidate again
        // on the next run. The sweep would starve permanently on one row, every fifteen minutes,
        // forever, with no repair path, because the reconciler IS the repair path.
        List<UserSubscription> rows = new ArrayList<>();
        rows.add(row("not-a-fake-token", SubscriptionState.ACTIVE, true));
        for (int i = 0; i < 24; i++) {
            rows.add(row("fake-active-pro", SubscriptionState.ON_HOLD, true));
        }
        givenCandidates(rows.toArray(UserSubscription[]::new));

        reconciler.sweep();

        verify(writer, times(24)).reconcile(any(), any(), any());
    }

    @Test
    void anUnacknowledgedEntitlingRowIsAcknowledgedAtGoogle() {
        // The wave-1 hole this component closes: one transient 5xx during acknowledge left an
        // account this database says is entitled for a year and Google has silently refunded —
        // after 3 days, or 5 MINUTES for a test purchase, which is every purchase on a closed track.
        FakePlaySubscriptionsApi play = new FakePlaySubscriptionsApi(catalog());
        SubscriptionReconciler withFake = new SubscriptionReconciler(subscriptions, writer,
                new SubscriptionSnapshots(catalog()), new SubscriptionLinkResolver(subscriptions, writer),
                play, properties(true), playProperties(PlayProperties.FAKE));
        UserSubscription row = row("fake-active-pro", SubscriptionState.ACTIVE, false);
        givenCandidates(row);

        withFake.sweep();

        assertThat(play.acknowledgedTokens()).contains("fake-active-pro");
        verify(writer).markAcknowledged(row.getId());
    }

    @Test
    void anExpiredRowIsNotAcknowledgedBecauseTheAutoRefundClockOnlyRunsWhileItIsLive() {
        FakePlaySubscriptionsApi play = new FakePlaySubscriptionsApi(catalog());
        SubscriptionReconciler withFake = new SubscriptionReconciler(subscriptions, writer,
                new SubscriptionSnapshots(catalog()), new SubscriptionLinkResolver(subscriptions, writer),
                play, properties(true), playProperties(PlayProperties.FAKE));
        UserSubscription row = row("fake-active-pro", SubscriptionState.EXPIRED, false);
        row.setEntitledUntil(Instant.now().minus(Duration.ofDays(1)));
        givenCandidates(row);

        withFake.sweep();

        assertThat(play.acknowledgedTokens()).isEmpty();
    }

    private void givenCandidates(UserSubscription... rows) {
        when(subscriptions.reconcileCandidates(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(rows));
        when(subscriptions.findByUserIdAndPurchaseToken(any(), anyString())).thenReturn(Optional.empty());
    }

    private UserSubscription row(String token, SubscriptionState state, boolean acknowledged) {
        return UserSubscription.builder()
                .id(UUID.randomUUID())
                .userId(owner)
                .purchaseToken(token)
                .productId("whereis_pro_annual")
                .tier(Plan.PRO)
                .provenance(PurchaseProvenance.PLAY_PURCHASE)
                .state(state)
                .entitledUntil(Instant.now().plus(Duration.ofDays(200)))
                .acknowledged(acknowledged)
                .verifiedAt(Instant.now().minus(Duration.ofHours(20)))
                .build();
    }

    /**
     * <strong>The gate that lets whereis deploy before Play Billing exists.</strong> With
     * {@code whereis.play.provider=disabled} every Play call refuses, so an unguarded tick would
     * throw on the first candidate row every fifteen minutes forever. Note what this asserts
     * beyond "it did nothing": the flag is ON, so only the billing gate can be what stopped it.
     */
    @Test
    void theScheduledSweepDoesNothingWhenBillingIsNotConfiguredEvenWithTheFlagOn() {
        SubscriptionReconciler off = new SubscriptionReconciler(subscriptions, writer,
                new SubscriptionSnapshots(catalog()), new SubscriptionLinkResolver(subscriptions, writer),
                new DisabledPlaySubscriptionsApi(), properties(true),
                playProperties(PlayProperties.DISABLED));

        off.sweep();

        verify(subscriptions, never()).reconcileCandidates(any(), any(), any(), any());
    }

    private static PlayProperties playProperties(String provider) {
        return new PlayProperties(provider, "az.technest.whereis", null, Duration.ofSeconds(10));
    }

    private static ReconcileProperties properties(boolean enabled) {
        return new ReconcileProperties(
                new ReconcileProperties.Reconcile(enabled, 25, null, null, null, Duration.ZERO),
                null, null, null);
    }

    private static PlanCatalog catalog() {
        Map<Plan, PlanCatalog.TierConfig> tiers = new EnumMap<>(Plan.class);
        tiers.put(Plan.FREE, new PlanCatalog.TierConfig(1, 100, 1, null));
        tiers.put(Plan.STANDARD, new PlanCatalog.TierConfig(3, 300, 3, "whereis_standard_annual"));
        tiers.put(Plan.PRO, new PlanCatalog.TierConfig(5, 600, 10, "whereis_pro_annual"));
        tiers.put(Plan.MAX, new PlanCatalog.TierConfig(10, null, 25, "whereis_max_annual"));
        tiers.put(Plan.UNLIMITED, new PlanCatalog.TierConfig(null, null, null, null));
        return new PlanCatalog(tiers);
    }
}
