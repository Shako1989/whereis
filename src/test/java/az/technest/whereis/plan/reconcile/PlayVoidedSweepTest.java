package az.technest.whereis.plan.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.PlanCatalog;
import az.technest.whereis.plan.PurchaseProvenance;
import az.technest.whereis.plan.SubscriptionState;
import az.technest.whereis.plan.SubscriptionWriter;
import az.technest.whereis.plan.UserSubscription;
import az.technest.whereis.plan.UserSubscriptionRepository;
import az.technest.whereis.plan.play.FakePlaySubscriptionsApi;
import az.technest.whereis.plan.play.PlayVoidedPurchase;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The refund backstop: the one line that makes it work, and the sweep that uses it.
 *
 * <p>The {@code type=1} half of the same story lives in
 * {@code az.technest.whereis.plan.play.GooglePlayVoidedRequestTest}, next to the adapter whose
 * request builder it reads: omitting that line is the most dangerous silent failure in this wave,
 * because the endpoint defaults to {@code type=0} (one-time products) and the sweep would then
 * return an empty list forever while every run succeeded and every metric stayed green.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlayVoidedSweepTest {

    private UserSubscriptionRepository subscriptions;
    private SubscriptionWriter writer;
    private FakePlaySubscriptionsApi play;
    private VoidedPurchaseSweeper sweeper;

    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        subscriptions = mock(UserSubscriptionRepository.class);
        writer = mock(SubscriptionWriter.class);
        play = new FakePlaySubscriptionsApi(catalog());
        sweeper = new VoidedPurchaseSweeper(subscriptions, writer, play, properties(true));
        when(writer.markVoided(any(), any(), any(), any())).thenReturn(true);
        when(subscriptions.findByPurchaseToken(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void theSweepDoesNothingAtAllWhenItsFlagIsOff() {
        VoidedPurchaseSweeper off = new VoidedPurchaseSweeper(subscriptions, writer, play, properties(false));
        play.enqueueVoid(voided("tok", Instant.now().minus(Duration.ofHours(1))));

        off.sweep();

        verify(subscriptions, never()).findByPurchaseToken(anyString());
    }

    @Test
    void aVoidInTheWindowWithALocalRowIsRevokedThroughTheSameWriteTheNotificationUses() {
        UserSubscription row = row(null);
        when(subscriptions.findByPurchaseToken("tok")).thenReturn(Optional.of(row));
        Instant voidedAt = Instant.now().minus(Duration.ofHours(2));
        play.enqueueVoid(voided("tok", voidedAt));

        sweeper.sweep();

        ArgumentCaptor<Long> watermark = ArgumentCaptor.forClass(Long.class);
        verify(writer).markVoided(any(), any(), any(), watermark.capture());
        // NULL: a sweep applies no notification, so it must not touch the RTDN high-water mark.
        assertThat(watermark.getValue()).isNull();
    }

    @Test
    void anAlreadyVoidedRowIsLeftAloneBecauseVoidedAtIsWriteOnce() {
        when(subscriptions.findByPurchaseToken("tok"))
                .thenReturn(Optional.of(row(Instant.now().minus(Duration.ofDays(1)))));
        play.enqueueVoid(voided("tok", Instant.now().minus(Duration.ofHours(2))));

        sweeper.sweep();

        verify(writer, never()).markVoided(any(), any(), any(), any());
    }

    @Test
    void aVoidForATokenWeHaveNeverSeenFabricatesNothing() {
        // A void can legitimately arrive BEFORE the purchase is known to us. The fixed 7-day
        // look-back is what re-applies it once the client's foreground sync creates the row.
        play.enqueueVoid(voided("unknown", Instant.now().minus(Duration.ofHours(2))));

        sweeper.sweep();

        verify(writer, never()).markVoided(any(), any(), any(), any());
    }

    @Test
    void aVoidOutsideTheLookBackWindowIsNotReturnedAtAll() {
        play.enqueueVoid(voided("tok", Instant.now().minus(Duration.ofDays(30))));
        when(subscriptions.findByPurchaseToken("tok")).thenReturn(Optional.of(row(null)));

        sweeper.sweep();

        verify(writer, never()).markVoided(any(), any(), any(), any());
    }

    @Test
    void everyPageIsFollowedSoAVoidOnThesecondPageIsStillApplied() {
        // The fake pages at two per response, so three voids prove the sweep actually follows
        // tokenPagination.nextPageToken rather than stopping at the first page.
        for (int i = 0; i < 3; i++) {
            play.enqueueVoid(voided("tok-" + i, Instant.now().minus(Duration.ofHours(i + 1))));
        }
        when(subscriptions.findByPurchaseToken("tok-2")).thenReturn(Optional.of(row(null)));

        sweeper.sweep();

        verify(writer).markVoided(any(), any(), any(), any());
    }

    private static PlayVoidedPurchase voided(String token, Instant at) {
        return new PlayVoidedPurchase(token, "GS.1", at, 1, 0);
    }

    private UserSubscription row(Instant voidedAt) {
        UserSubscription row = UserSubscription.builder()
                .id(UUID.randomUUID())
                .userId(owner)
                .purchaseToken("tok")
                .productId("whereis_pro_annual")
                .tier(Plan.PRO)
                .provenance(PurchaseProvenance.PLAY_PURCHASE)
                .state(SubscriptionState.ACTIVE)
                .entitledUntil(Instant.now().plus(Duration.ofDays(300)))
                .verifiedAt(Instant.now())
                .build();
        row.setVoidedAt(voidedAt);
        return row;
    }

    private static ReconcileProperties properties(boolean enabled) {
        return new ReconcileProperties(null,
                new ReconcileProperties.VoidedSweep(enabled, null, 20, Duration.ZERO), null);
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
