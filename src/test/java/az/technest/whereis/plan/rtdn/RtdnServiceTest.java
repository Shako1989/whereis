package az.technest.whereis.plan.rtdn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import az.technest.whereis.plan.play.FakePlaySubscriptionsApi;
import az.technest.whereis.plan.play.PlayProperties;
import az.technest.whereis.plan.play.PlaySubscriptionsApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
 * The ack decision table, the ordering guards and the revoke path, offline.
 *
 * <p>The ONE property every case here is really about: <strong>nothing escapes</strong>. Every
 * failure this flow can produce has to come back as a {@link RtdnService.Verdict} with a status from
 * the table, because {@code GlobalExceptionHandler} would otherwise turn a {@code PlayApiException}
 * into 502 and a {@code PlayPurchaseInvalidException} into 400 — statuses Pub/Sub nacks, on a
 * message whose ledger row was never finished.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RtdnServiceTest {

    private static final String PACKAGE = "az.technest.whereis";

    private ObjectMapper mapper;
    private PlayNotificationLedger ledger;
    private UserSubscriptionRepository subscriptions;
    private SubscriptionWriter writer;
    private PlaySubscriptionsApi play;
    private RtdnService service;

    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ledger = mock(PlayNotificationLedger.class);
        subscriptions = mock(UserSubscriptionRepository.class);
        writer = mock(SubscriptionWriter.class);
        play = new FakePlaySubscriptionsApi(catalog());
        PlanCatalog planCatalog = catalog();
        service = new RtdnService(mapper, ledger, subscriptions, writer,
                new SubscriptionSnapshots(planCatalog),
                new SubscriptionLinkResolver(subscriptions, writer),
                play,
                new PlayProperties("fake", PACKAGE, null, null),
                properties());
        when(subscriptions.findByPurchaseToken(anyString())).thenReturn(Optional.empty());
        when(writer.markVoided(any(), any(), any(), any())).thenReturn(true);
        when(writer.applyNotification(any(), any(), anyLong(), any())).thenReturn(true);
        when(writer.find(any())).thenReturn(Optional.empty());
        when(ledger.load(anyString())).thenReturn(Optional.empty());
    }

    // ---------------------------------------------------------------- the un-ledgered acks

    @Test
    void anUnreadableEnvelopeIsAckedWithNoLedgerRowBecauseThereIsNoKeyToWriteUnder() {
        RtdnService.Verdict verdict = service.handle("{".getBytes(StandardCharsets.UTF_8));

        assertThat(verdict.status()).isEqualTo(200);
        assertThat(verdict.outcome()).isNull();
        verify(ledger, never()).insertNew(any());
    }

    @Test
    void anEnvelopeWithNoMessageIdIsAckedWithNoLedgerRow() {
        RtdnService.Verdict verdict = service.handle(
                "{\"message\":{\"data\":\"e30=\"}}".getBytes(StandardCharsets.UTF_8));

        assertThat(verdict.status()).isEqualTo(200);
        verify(ledger, never()).insertNew(any());
    }

    // ---------------------------------------------------------------- MALFORMED

    @Test
    void dataThatIsNotBase64IsRecordedAsMalformedAtTheCeilingAndAcked() {
        // THE ROW V10 COULD NOT WRITE. It has no event time, no package name and no decoded
        // payload, which is precisely why V11 relaxed those NOT NULLs for this outcome alone.
        RtdnService.Verdict verdict = service.handle(envelope("m-1", "!!!not-base64!!!"));

        assertThat(verdict.status()).isEqualTo(200);
        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.MALFORMED);

        PlayNotification row = captureInsert();
        assertThat(row.getOutcome()).isEqualTo(PlayNotificationOutcome.MALFORMED);
        assertThat(row.getProcessedAt()).isNull();
        assertThat(row.getAttempts()).isEqualTo(10);
        assertThat(row.getEventTimeMillis()).isNull();
        assertThat(row.getPackageName()).isNull();
        // The payload is the RAW ENVELOPE — the only truthful thing there is to keep.
        assertThat(row.getPayload()).containsKey("message");
    }

    @Test
    void anUnparseableEventTimeIsMalformedRatherThanAFiveHundred() {
        RtdnService.Verdict verdict = service.handle(envelope("m-2", encode(
                "{\"packageName\":\"" + PACKAGE + "\",\"eventTimeMillis\":\"not-a-number\","
                        + "\"testNotification\":{}}")));

        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.MALFORMED);
        assertThat(captureInsert().getAttempts()).isEqualTo(10);
    }

    @Test
    void aNotificationThatIsNotJsonIsMalformed() {
        RtdnService.Verdict verdict = service.handle(envelope("m-3", encode("this is not json")));

        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.MALFORMED);
    }

    // ---------------------------------------------------------------- IGNORED

    @Test
    void anotherAppsPackageIsIgnoredBecauseASharedTopicIsAConsoleProblem() {
        RtdnService.Verdict verdict = service.handle(envelope("m-4", encode(
                "{\"packageName\":\"com.example.other\",\"eventTimeMillis\":\"1000\","
                        + "\"subscriptionNotification\":{\"notificationType\":2,"
                        + "\"purchaseToken\":\"fake-active-pro\"}}")));

        assertThat(verdict.status()).isEqualTo(200);
        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.IGNORED);
        verify(writer, never()).applyNotification(any(), any(), anyLong(), any());
    }

    @Test
    void aTestNotificationIsIgnoredAndRecordedSoTheConsoleButtonCanBeConfirmed() {
        RtdnService.Verdict verdict = service.handle(envelope("m-5", encode(
                "{\"packageName\":\"" + PACKAGE + "\",\"eventTimeMillis\":\"1000\","
                        + "\"testNotification\":{\"version\":\"1.0\"}}")));

        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.IGNORED);
        assertThat(captureInsert().getNotificationKind()).isEqualTo(PlayNotificationKind.TEST);
    }

    @Test
    void aOneTimeProductNotificationIsIgnoredButRecordedInFull() {
        RtdnService.Verdict verdict = service.handle(envelope("m-6", encode(
                "{\"packageName\":\"" + PACKAGE + "\",\"eventTimeMillis\":\"1000\","
                        + "\"oneTimeProductNotification\":{\"notificationType\":1,"
                        + "\"purchaseToken\":\"t\",\"sku\":\"whatever\"}}")));

        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.IGNORED);
        assertThat(captureInsert().getNotificationKind()).isEqualTo(PlayNotificationKind.ONE_TIME_PRODUCT);
    }

    // ---------------------------------------------------------------- the siblings

    @Test
    void aVoidedPurchaseNotificationIsRecognisedAsItsOwnSiblingAndRevokes() {
        // The failure this whole path exists to prevent: a handler that reads only
        // subscriptionNotification ignores every refund and looks perfectly healthy doing it.
        UserSubscription row = row(SubscriptionState.ACTIVE, null);
        when(subscriptions.findByPurchaseToken("tok")).thenReturn(Optional.of(row));

        RtdnService.Verdict verdict = service.handle(envelope("m-7", encode(
                "{\"packageName\":\"" + PACKAGE + "\",\"eventTimeMillis\":\"5000\","
                        + "\"voidedPurchaseNotification\":{\"purchaseToken\":\"tok\","
                        + "\"orderId\":\"GS.1\",\"productType\":1,\"refundType\":1}}")));

        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.APPLIED);
        verify(writer).markVoided(eq(row.getId()), any(), eq("GS.1"), eq(5000L));
        assertThat(captureInsert().getNotificationKind()).isEqualTo(PlayNotificationKind.VOIDED_PURCHASE);
    }

    @Test
    void aVoidWithNoProductTypeAtAllIsStillAppliedBecauseTheTokenDecidesNotTheField() {
        // `productType != 1` fails OPEN: an absent field makes it true and every refund is silently
        // ignored. A hit in user_subscriptions IS a subscription void by construction.
        UserSubscription row = row(SubscriptionState.ACTIVE, null);
        when(subscriptions.findByPurchaseToken("tok")).thenReturn(Optional.of(row));

        RtdnService.Verdict verdict = service.handle(envelope("m-8", encode(
                "{\"packageName\":\"" + PACKAGE + "\",\"eventTimeMillis\":\"5000\","
                        + "\"voidedPurchaseNotification\":{\"purchaseToken\":\"tok\"}}")));

        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.APPLIED);
        verify(writer).markVoided(eq(row.getId()), any(), any(), eq(5000L));
    }

    @Test
    void aVoidOfAnExplicitOneTimeProductWithNoLocalRowIsIgnored() {
        RtdnService.Verdict verdict = service.handle(envelope("m-9", encode(
                "{\"packageName\":\"" + PACKAGE + "\",\"eventTimeMillis\":\"5000\","
                        + "\"voidedPurchaseNotification\":{\"purchaseToken\":\"tok\",\"productType\":2}}")));

        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.IGNORED);
    }

    @Test
    void aVoidForATokenWeHaveNeverSeenIsNoLocalRowAndFabricatesNothing() {
        RtdnService.Verdict verdict = service.handle(envelope("m-10", encode(
                "{\"packageName\":\"" + PACKAGE + "\",\"eventTimeMillis\":\"5000\","
                        + "\"voidedPurchaseNotification\":{\"purchaseToken\":\"tok\",\"productType\":1}}")));

        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.NO_LOCAL_ROW);
        verify(writer, never()).markVoided(any(), any(), any(), any());
    }

    @Test
    void aVoidBelowTheRowsWatermarkStillRevokes() {
        // THE CORRECTION THAT MATTERS MOST HERE. A refund and its accompanying CANCELED are emitted
        // milliseconds apart and Pub/Sub guarantees no order. If the cancel lands first the
        // watermark advances, and a watermark-guarded revoke would be discarded as stale — leaving
        // the row CANCELED with a future expiry, which ENTITLES. A refunded annual subscriber would
        // keep PRO until the six-hourly sweep happened to repair it.
        UserSubscription row = row(SubscriptionState.CANCELED, null);
        row.setLastEventTime(9_000L);
        when(subscriptions.findByPurchaseToken("tok")).thenReturn(Optional.of(row));

        RtdnService.Verdict verdict = service.handle(envelope("m-11", encode(
                "{\"packageName\":\"" + PACKAGE + "\",\"eventTimeMillis\":\"1000\","
                        + "\"voidedPurchaseNotification\":{\"purchaseToken\":\"tok\",\"productType\":1}}")));

        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.APPLIED);
        verify(writer).markVoided(eq(row.getId()), any(), any(), eq(1000L));
    }

    @Test
    void aSecondVoidUnderADifferentMessageIdChangesNothingBecauseVoidedAtIsWriteOnce() {
        UserSubscription row = row(SubscriptionState.ACTIVE, Instant.now());
        when(subscriptions.findByPurchaseToken("tok")).thenReturn(Optional.of(row));
        when(writer.markVoided(any(), any(), any(), any())).thenReturn(false);

        RtdnService.Verdict verdict = service.handle(envelope("m-12", encode(
                "{\"packageName\":\"" + PACKAGE + "\",\"eventTimeMillis\":\"6000\","
                        + "\"voidedPurchaseNotification\":{\"purchaseToken\":\"tok\",\"productType\":1}}")));

        assertThat(verdict.status()).isEqualTo(200);
        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.DISCARDED_STALE);
    }

    @Test
    void subscriptionRevokedTakesTheSameRevokePathWithNoGoogleCall() {
        UserSubscription row = row(SubscriptionState.ACTIVE, null);
        when(subscriptions.findByPurchaseToken("fake-outage-pro")).thenReturn(Optional.of(row));

        // The token would make the fake Play port throw. Reaching APPLIED proves no call was made —
        // a refund must never be blocked by a Play API outage.
        RtdnService.Verdict verdict = service.handle(envelope("m-13", encode(
                "{\"packageName\":\"" + PACKAGE + "\",\"eventTimeMillis\":\"7000\","
                        + "\"subscriptionNotification\":{\"notificationType\":12,"
                        + "\"purchaseToken\":\"fake-outage-pro\"}}")));

        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.APPLIED);
    }

    // ---------------------------------------------------------------- REFRESH and ordering

    @Test
    void aRenewalRefreshesTheRowFromGoogle() {
        UserSubscription row = row(SubscriptionState.ON_HOLD, null);
        when(subscriptions.findByPurchaseToken("fake-active-pro")).thenReturn(Optional.of(row));

        RtdnService.Verdict verdict = service.handle(refresh("m-14", 8000L, 2, "fake-active-pro"));

        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.APPLIED);
        ArgumentCaptor<SubscriptionWriter.Snapshot> captor =
                ArgumentCaptor.forClass(SubscriptionWriter.Snapshot.class);
        verify(writer).applyNotification(eq(row.getId()), captor.capture(), eq(8000L), any());
        assertThat(captor.getValue().state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(captor.getValue().tier()).isEqualTo(Plan.PRO);
    }

    @Test
    void anOlderEventIsDiscardedWithoutEvenAskingGoogle() {
        UserSubscription row = row(SubscriptionState.EXPIRED, null);
        row.setLastEventTime(9_000L);
        when(subscriptions.findByPurchaseToken("fake-active-pro")).thenReturn(Optional.of(row));

        RtdnService.Verdict verdict = service.handle(refresh("m-15", 1000L, 2, "fake-active-pro"));

        assertThat(verdict.status()).isEqualTo(200);
        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.DISCARDED_STALE);
        verify(writer, never()).applyNotification(any(), any(), anyLong(), any());
    }

    @Test
    void aWriteTheGuardRefusesInsideTheTransactionIsDiscardedStaleToo() {
        // The pre-check above only saves a round trip; the REAL guard lives inside the write
        // transaction after SELECT … FOR UPDATE, and its refusal must reach the ack table too.
        UserSubscription row = row(SubscriptionState.ACTIVE, null);
        when(subscriptions.findByPurchaseToken("fake-active-pro")).thenReturn(Optional.of(row));
        when(writer.applyNotification(any(), any(), anyLong(), any())).thenReturn(false);

        RtdnService.Verdict verdict = service.handle(refresh("m-16", 8000L, 2, "fake-active-pro"));

        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.DISCARDED_STALE);
    }

    @Test
    void aTokenWithNoLocalRowAndNoLinkedTokenIsNoLocalRow() {
        RtdnService.Verdict verdict = service.handle(refresh("m-17", 8000L, 4, "fake-active-pro"));

        assertThat(verdict.status()).isEqualTo(200);
        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.NO_LOCAL_ROW);
        verify(writer, never()).upsert(any());
    }

    @Test
    void anUpgradeWhoseLinkedRowCarriesNoAccountHashFailsClosed() {
        // The opposite of the verify endpoint's rule, deliberately: there the caller's JWT proves
        // the claim, here there is no caller, and our client makes setObfuscatedAccountId mandatory
        // — so a missing hash means the purchase did not come from us. Binding wrongly is permanent.
        UserSubscription linked = row(SubscriptionState.ACTIVE, null);
        when(subscriptions.findByPurchaseToken("fake-linked-pro")).thenReturn(Optional.empty());
        when(subscriptions.findByPurchaseToken("fake-linked-pro-previous")).thenReturn(Optional.of(linked));

        RtdnService.Verdict verdict = service.handle(refresh("m-18", 8000L, 4, "fake-linked-pro"));

        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.NO_LOCAL_ROW);
        verify(writer, never()).upsert(any());
    }

    @Test
    void anUpgradeWhoseHashMatchesTheLinkedRowsOwnerIsAttributedAndCreated() {
        UserSubscription linked = row(SubscriptionState.ACTIVE, null);
        String token = "fake-linked-pro@" + az.technest.whereis.plan.play.PlayAccountHash.of(owner);
        when(subscriptions.findByPurchaseToken(token)).thenReturn(Optional.empty());
        when(subscriptions.findByPurchaseToken(token + "-previous")).thenReturn(Optional.of(linked));
        when(writer.upsert(any())).thenReturn(linked);

        RtdnService.Verdict verdict = service.handle(refresh("m-19", 8000L, 4, token));

        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.APPLIED);
        verify(writer).upsert(any());
        // The watermark is advanced SEPARATELY, never as a Snapshot component.
        verify(writer).advanceWatermark(linked.getId(), 8000L);
    }

    // ---------------------------------------------------------------- redelivery and failure

    @Test
    void aRedeliveryThatLosesTheLeaseIsAckedWithNothingTouched() {
        UserSubscription row = row(SubscriptionState.ACTIVE, null);
        when(subscriptions.findByPurchaseToken("fake-active-pro")).thenReturn(Optional.of(row));
        doThrowOnInsert();
        when(ledger.claim(anyString(), anyInt())).thenReturn(0);

        RtdnService.Verdict verdict = service.handle(refresh("m-20", 8000L, 2, "fake-active-pro"));

        assertThat(verdict.status()).isEqualTo(200);
        assertThat(verdict.outcome()).isNull();
        verify(writer, never()).applyNotification(any(), any(), anyLong(), any());
        verify(ledger, never()).finish(anyString(), any(), any());
    }

    @Test
    void aRedeliveryThatWinsTheLeaseResumesProcessing() {
        UserSubscription row = row(SubscriptionState.ACTIVE, null);
        when(subscriptions.findByPurchaseToken("fake-active-pro")).thenReturn(Optional.of(row));
        doThrowOnInsert();
        when(ledger.claim(anyString(), anyInt())).thenReturn(1);

        RtdnService.Verdict verdict = service.handle(refresh("m-21", 8000L, 2, "fake-active-pro"));

        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.APPLIED);
        verify(writer, times(1)).applyNotification(any(), any(), anyLong(), any());
    }

    @Test
    void aPlayOutageIsAFiveHundredUnderTheCeilingRatherThanTheFiveHundredAndTwoTheAdviceWouldGive() {
        UserSubscription row = row(SubscriptionState.ACTIVE, null);
        when(subscriptions.findByPurchaseToken("fake-outage-pro")).thenReturn(Optional.of(row));
        when(ledger.load("m-22")).thenReturn(Optional.of(ledgerRow(1)));

        RtdnService.Verdict verdict = service.handle(refresh("m-22", 8000L, 2, "fake-outage-pro"));

        assertThat(verdict.status()).isEqualTo(500);
        assertThat(verdict.outcome()).isNull();
        verify(ledger).recordFailure(eq("m-22"), anyString());
    }

    @Test
    void theSameOutageAtTheCeilingIsAckedAsFailedSoTheMessageStopsLooping() {
        UserSubscription row = row(SubscriptionState.ACTIVE, null);
        when(subscriptions.findByPurchaseToken("fake-outage-pro")).thenReturn(Optional.of(row));
        when(ledger.load("m-23")).thenReturn(Optional.of(ledgerRow(10)));

        RtdnService.Verdict verdict = service.handle(refresh("m-23", 8000L, 2, "fake-outage-pro"));

        assertThat(verdict.status()).isEqualTo(200);
        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.FAILED);
    }

    @Test
    void aTokenGoogleAnswersFourOhFourForIsIgnoredRatherThanTheFourHundredTheAdviceWouldGive() {
        UserSubscription row = row(SubscriptionState.ACTIVE, null);
        when(subscriptions.findByPurchaseToken("not-a-fake-token")).thenReturn(Optional.of(row));

        RtdnService.Verdict verdict = service.handle(refresh("m-24", 8000L, 2, "not-a-fake-token"));

        assertThat(verdict.status()).isEqualTo(200);
        assertThat(verdict.outcome()).isEqualTo(PlayNotificationOutcome.IGNORED);
        // Nothing is revoked on the strength of a Google error.
        verify(writer, never()).markVoided(any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- helpers

    private void doThrowOnInsert() {
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("dup"))
                .when(ledger).insertNew(any());
    }

    private PlayNotification captureInsert() {
        ArgumentCaptor<PlayNotification> captor = ArgumentCaptor.forClass(PlayNotification.class);
        verify(ledger).insertNew(captor.capture());
        return captor.getValue();
    }

    private byte[] refresh(String messageId, long eventTime, int type, String token) {
        return envelope(messageId, encode(
                "{\"packageName\":\"" + PACKAGE + "\",\"eventTimeMillis\":\"" + eventTime + "\","
                        + "\"subscriptionNotification\":{\"notificationType\":" + type + ","
                        + "\"purchaseToken\":\"" + token + "\",\"subscriptionId\":\"whereis_pro_annual\"}}"));
    }

    private byte[] envelope(String messageId, String data) {
        return ("{\"message\":{\"messageId\":\"" + messageId + "\",\"data\":\"" + data
                + "\",\"publishTime\":\"2026-09-19T10:00:00Z\"},\"subscription\":\"projects/p/subscriptions/s\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String encode(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private UserSubscription row(SubscriptionState state, Instant voidedAt) {
        UserSubscription row = UserSubscription.builder()
                .id(UUID.randomUUID())
                .userId(owner)
                .purchaseToken("tok")
                .productId("whereis_pro_annual")
                .tier(Plan.PRO)
                .provenance(PurchaseProvenance.PLAY_PURCHASE)
                .state(state)
                .entitledUntil(Instant.now().plus(Duration.ofDays(300)))
                .verifiedAt(Instant.now().minus(Duration.ofHours(20)))
                .build();
        row.setVoidedAt(voidedAt);
        return row;
    }

    private static PlayNotification ledgerRow(int attempts) {
        return PlayNotification.builder().messageId("x").attempts(attempts).build();
    }

    private static RtdnProperties properties() {
        return new RtdnProperties(true, "fake", "secret", "whereis-rtdn", "svc@example.com",
                null, "bearer", 10, 262144);
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
