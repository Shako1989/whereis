package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The four writes wave 2 adds to {@code SubscriptionWriter}, and the guards that stop the RTDN
 * handler, the reconciler and the verify endpoint from fighting over the same row.
 *
 * <p>Each test here corresponds to one of the four anti-fight mechanisms documented on the writer;
 * between them they are the reason a chargeback cannot be silently undone and a stale Google answer
 * cannot resurrect an expired row.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SubscriptionLifecycleWriterTest {

    private UserSubscriptionRepository subscriptions;
    private SubscriptionWriter writer;

    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        subscriptions = mock(UserSubscriptionRepository.class);
        writer = new SubscriptionWriter(subscriptions);
        when(subscriptions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ------------------------------------------------------------------ markVoided

    @Test
    void markVoidedIsWriteOnceSoASecondRefundCannotMoveTheTimestamp() {
        // A second delivery, the sweep finding the same purchase, and a SUBSCRIPTION_REVOKED for
        // the same refund are all NORMAL, and none of them may move voided_at.
        Instant first = Instant.parse("2026-03-01T00:00:00Z");
        UserSubscription row = row();
        given(row);

        assertThat(writer.markVoided(row.getId(), first, "GS.1", 1000L)).isTrue();
        assertThat(writer.markVoided(row.getId(), Instant.parse("2026-01-01T00:00:00Z"), "GS.2", 2000L))
                .isFalse();

        assertThat(row.getVoidedAt()).isEqualTo(first);
        assertThat(row.getLatestOrderId()).isEqualTo("GS.1");
    }

    @Test
    void markVoidedTouchesNeitherStateNorTierNorVerifiedAt() {
        // We did not ask Google anything, so we may not claim to have verified anything.
        // verified_at is the reconciler's compare-and-set token, and corrupting it here is how the
        // two components start fighting.
        UserSubscription row = row();
        Instant verifiedAt = row.getVerifiedAt();
        given(row);

        writer.markVoided(row.getId(), Instant.now(), "GS.1", 1000L);

        assertThat(row.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(row.getTier()).isEqualTo(Plan.PRO);
        assertThat(row.getVerifiedAt()).isEqualTo(verifiedAt);
    }

    @Test
    void aSweepDrivenVoidPassesNoEventTimeAndLeavesTheWatermarkAlone() {
        // The sweep applies no notification, so it has no eventTimeMillis and must not invent one.
        // The two entry points share the method; only this argument differs.
        UserSubscription row = row();
        row.setLastEventTime(5_000L);
        given(row);

        writer.markVoided(row.getId(), Instant.now(), "GS.1", null);

        assertThat(row.getLastEventTime()).isEqualTo(5_000L);
    }

    @Test
    void aNotificationDrivenVoidAdvancesTheWatermarkButNeverRewindsIt() {
        UserSubscription row = row();
        row.setLastEventTime(9_000L);
        given(row);

        writer.markVoided(row.getId(), Instant.now(), null, 1_000L);
        assertThat(row.getLastEventTime()).isEqualTo(9_000L);

        writer.markVoided(row.getId(), Instant.now(), null, 12_000L);
        assertThat(row.getLastEventTime()).isEqualTo(12_000L);
    }

    // ------------------------------------------------------------------ applyNotification

    @Test
    void applyNotificationRefusesAnEventThatIsNotStrictlyNewerThanTheWatermark() {
        // Strictly `>`, and that is load-bearing: two concurrent deliveries of the same message
        // carry the SAME eventTimeMillis, so the second is discarded by the guard instead of
        // re-applying.
        UserSubscription row = row();
        row.setLastEventTime(5_000L);
        given(row);

        assertThat(writer.applyNotification(row.getId(), snapshot(SubscriptionState.EXPIRED),
                5_000L, row.getVerifiedAt())).isFalse();
        assertThat(writer.applyNotification(row.getId(), snapshot(SubscriptionState.EXPIRED),
                4_999L, row.getVerifiedAt())).isFalse();
        assertThat(row.getState()).isEqualTo(SubscriptionState.ACTIVE);

        assertThat(writer.applyNotification(row.getId(), snapshot(SubscriptionState.EXPIRED),
                5_001L, row.getVerifiedAt())).isTrue();
        assertThat(row.getState()).isEqualTo(SubscriptionState.EXPIRED);
        assertThat(row.getLastEventTime()).isEqualTo(5_001L);
    }

    @Test
    void applyNotificationRefusesWhenAnotherWriterMovedVerifiedAtDuringTheGoogleRoundTrip() {
        // The resurrection case. The reconciler deliberately never moves the watermark, so WITHOUT
        // this compare-and-set a handler whose play.get() happened BEFORE a reconcile could commit
        // afterwards and bring an expired row back as ACTIVE with a stale expiry — and the
        // watermark check would pass, because nothing moved it.
        UserSubscription row = row();
        given(row);

        boolean written = writer.applyNotification(row.getId(), snapshot(SubscriptionState.ACTIVE),
                5_000L, Instant.parse("2020-01-01T00:00:00Z"));

        assertThat(written).isFalse();
        assertThat(row.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(row.getLastEventTime()).isNull();
    }

    @Test
    void applyNotificationNeverClearsVoidedAt() {
        // The other half of write-once: a SUBSCRIPTION_RENEWED arriving after a chargeback must not
        // silently un-refund the account. voided_at is simply not a Snapshot component.
        UserSubscription row = row();
        Instant voidedAt = Instant.parse("2026-03-01T00:00:00Z");
        row.setVoidedAt(voidedAt);
        given(row);

        writer.applyNotification(row.getId(), snapshot(SubscriptionState.ACTIVE), 5_000L,
                row.getVerifiedAt());

        assertThat(row.getVoidedAt()).isEqualTo(voidedAt);
    }

    // ------------------------------------------------------------------ reconcile

    @Test
    void reconcileWritesNothingWhenVerifiedAtMovedUnderIt() {
        UserSubscription row = row();
        given(row);

        assertThat(writer.reconcile(row.getId(), snapshot(SubscriptionState.EXPIRED),
                Instant.parse("2020-01-01T00:00:00Z"))).isFalse();
        assertThat(row.getState()).isEqualTo(SubscriptionState.ACTIVE);
    }

    @Test
    void reconcileRefusesToRunAgainstAVoidedOrSupersededRow() {
        // A chargeback that lands mid-round-trip must not be undone by a Google response that still
        // says ACTIVE.
        UserSubscription voided = row();
        voided.setVoidedAt(Instant.now());
        given(voided);
        assertThat(writer.reconcile(voided.getId(), snapshot(SubscriptionState.ACTIVE),
                voided.getVerifiedAt())).isFalse();

        UserSubscription superseded = row();
        superseded.setSupersededBy(UUID.randomUUID());
        given(superseded);
        assertThat(writer.reconcile(superseded.getId(), snapshot(SubscriptionState.ACTIVE),
                superseded.getVerifiedAt())).isFalse();
    }

    @Test
    void reconcileNeverWritesTheWatermark() {
        // It applies no notification. Writing it would push the high-water mark ahead of
        // notifications still in flight and the handler would discard them all, silently and
        // unrecoverably — the exact trap V10 documented for the verify endpoint.
        UserSubscription row = row();
        given(row);

        assertThat(writer.reconcile(row.getId(), snapshot(SubscriptionState.CANCELED),
                row.getVerifiedAt())).isTrue();

        assertThat(row.getState()).isEqualTo(SubscriptionState.CANCELED);
        assertThat(row.getLastEventTime()).isNull();
    }

    @Test
    void neitherUpsertNorRefreshOwnedTouchesTheWatermarkOfARowThatAlreadyHasOne() {
        // The verify endpoint runs on EVERY app foreground (PurchaseSyncer posts the token each
        // time). If Snapshot carried lastEventTime, every one of those would write null over the
        // handler's high-water mark.
        UserSubscription row = row();
        row.setLastEventTime(7_777L);
        when(subscriptions.findByPurchaseToken("tok")).thenReturn(Optional.of(row));

        writer.upsert(snapshot(SubscriptionState.ACTIVE));
        assertThat(row.getLastEventTime()).isEqualTo(7_777L);

        writer.refreshOwned(snapshot(SubscriptionState.ACTIVE), owner);
        assertThat(row.getLastEventTime()).isEqualTo(7_777L);
    }

    @Test
    void aVerifyPostPreservesThePendingProductTheHandlerWrote() {
        // pendingProductId MUST be a Snapshot component: apply() overwrites every mutable field, so
        // a component left off would be nulled out on every foreground and the plan screen's
        // "Pro until 14 March, then Standard" would appear and disappear at random.
        UserSubscription row = row();
        when(subscriptions.findByPurchaseToken("tok")).thenReturn(Optional.of(row));

        writer.upsert(new SubscriptionWriter.Snapshot(owner, "tok", "whereis_pro_annual", Plan.PRO,
                PurchaseProvenance.PLAY_PURCHASE, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(300)), true, null, false, "GPA.1", Instant.now(),
                "whereis_standard_annual"));

        assertThat(row.getPendingProductId()).isEqualTo("whereis_standard_annual");
    }

    // ------------------------------------------------------------------ markSuperseded

    @Test
    void markSupersededRefusesACrossUserLink() {
        // linkedPurchaseToken is scoped to a PLAY account, not a whereis account. A cross-user chain
        // would violate V10's composite self-FK and abort the single-transaction
        // AccountDeletionService on the Play-mandated DELETE /users/me.
        UserSubscription old = row();
        UserSubscription replacement = row();
        setUserId(replacement, UUID.randomUUID());
        given(old);
        when(subscriptions.findById(replacement.getId())).thenReturn(Optional.of(replacement));

        assertThat(writer.markSuperseded(old.getId(), replacement.getId())).isFalse();
        assertThat(old.getSupersededBy()).isNull();
    }

    @Test
    void markSupersededRefusesARowThatIsAlreadySuperseded() {
        UserSubscription old = row();
        old.setSupersededBy(UUID.randomUUID());
        UserSubscription replacement = row();
        given(old);
        when(subscriptions.findById(replacement.getId())).thenReturn(Optional.of(replacement));

        assertThat(writer.markSuperseded(old.getId(), replacement.getId())).isFalse();
    }

    @Test
    void markSupersededPointsTheOldRowAtTheNewOneAndTouchesNothingElse() {
        UserSubscription old = row();
        UserSubscription replacement = row();
        Instant verifiedAt = old.getVerifiedAt();
        given(old);
        when(subscriptions.findById(replacement.getId())).thenReturn(Optional.of(replacement));

        assertThat(writer.markSuperseded(old.getId(), replacement.getId())).isTrue();
        assertThat(old.getSupersededBy()).isEqualTo(replacement.getId());
        assertThat(old.getVerifiedAt()).isEqualTo(verifiedAt);
        assertThat(old.getLastEventTime()).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private void given(UserSubscription row) {
        when(subscriptions.findForUpdate(row.getId())).thenReturn(Optional.of(row));
        when(subscriptions.findById(row.getId())).thenReturn(Optional.of(row));
    }

    private SubscriptionWriter.Snapshot snapshot(SubscriptionState state) {
        return new SubscriptionWriter.Snapshot(owner, "tok", "whereis_pro_annual", Plan.PRO,
                PurchaseProvenance.PLAY_PURCHASE, state, Instant.now().plus(Duration.ofDays(300)),
                true, null, false, "GPA.1", Instant.now(), null);
    }

    private UserSubscription row() {
        return UserSubscription.builder()
                .id(UUID.randomUUID())
                .userId(owner)
                .purchaseToken("tok")
                .productId("whereis_pro_annual")
                .tier(Plan.PRO)
                .provenance(PurchaseProvenance.PLAY_PURCHASE)
                .state(SubscriptionState.ACTIVE)
                .entitledUntil(Instant.now().plus(Duration.ofDays(300)))
                .verifiedAt(Instant.parse("2026-09-19T00:00:00Z"))
                .build();
    }

    /** {@code userId} has no setter by design; only a test needs to forge a foreign owner. */
    private static void setUserId(UserSubscription row, UUID userId) {
        try {
            java.lang.reflect.Field field = UserSubscription.class.getDeclaredField("userId");
            field.setAccessible(true);
            field.set(row, userId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
