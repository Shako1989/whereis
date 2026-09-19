package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.plan.PlanCatalog.TierConfig;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.plan.dto.PurchaseVerificationRequest;
import az.technest.whereis.plan.play.FakePlaySubscriptionsApi;
import az.technest.whereis.plan.play.DisabledPlaySubscriptionsApi;
import az.technest.whereis.plan.play.PlayAccountHash;
import az.technest.whereis.plan.play.PlayProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/**
 * Every row of the endpoint's failure table, offline, against the real port fake — so these
 * exercise the actual branches of the verification flow rather than a mock's idea of them.
 */
class PurchaseVerificationServiceTest {

    private static final String PRO = "whereis_pro_annual";
    private static final String MAX = "whereis_max_annual";

    private final UUID caller = UUID.randomUUID();
    private final UUID otherUser = UUID.randomUUID();

    private UserSubscriptionRepository subscriptions;
    private SubscriptionWriter writer;
    private FakePlaySubscriptionsApi play;
    private PlanLimitEnforcer planLimits;
    private PlanCatalog catalog;
    private PurchaseVerificationService service;

    private static PlanCatalog shippedCatalog() {
        Map<Plan, TierConfig> tiers = new EnumMap<>(Plan.class);
        tiers.put(Plan.FREE, new TierConfig(1, 100, null));
        tiers.put(Plan.STANDARD, new TierConfig(3, 300, "whereis_standard_annual"));
        tiers.put(Plan.PRO, new TierConfig(5, 600, PRO));
        tiers.put(Plan.MAX, new TierConfig(10, null, MAX));
        tiers.put(Plan.UNLIMITED, new TierConfig(null, null, null));
        return new PlanCatalog(tiers);
    }

    @BeforeEach
    void setUp() {
        subscriptions = mock(UserSubscriptionRepository.class);
        writer = mock(SubscriptionWriter.class);
        play = new FakePlaySubscriptionsApi(shippedCatalog());
        planLimits = mock(PlanLimitEnforcer.class);
        catalog = shippedCatalog();
        service = new PurchaseVerificationService(subscriptions, writer, play,
                properties(PlayProperties.FAKE), catalog, planLimits,
                new SubscriptionLinkResolver(subscriptions, writer));

        when(subscriptions.findByPurchaseToken(any())).thenReturn(Optional.empty());
        when(writer.upsert(any())).thenAnswer(invocation -> rowOf(invocation.getArgument(0)));
        when(planLimits.statusOf(any())).thenReturn(mock(PlanStatusResponse.class));
    }

    private static PlayProperties properties(String provider) {
        return new PlayProperties(provider, "az.technest.whereis", null, Duration.ofSeconds(10));
    }

    private static UserSubscription rowOf(SubscriptionWriter.Snapshot snapshot) {
        return UserSubscription.builder()
                .id(UUID.randomUUID())
                .userId(snapshot.userId())
                .purchaseToken(snapshot.purchaseToken())
                .productId(snapshot.productId())
                .tier(snapshot.tier())
                .provenance(snapshot.provenance())
                .state(snapshot.state())
                .entitledUntil(snapshot.entitledUntil())
                .acknowledged(snapshot.acknowledged())
                .linkedPurchaseToken(snapshot.linkedPurchaseToken())
                .testPurchase(snapshot.testPurchase())
                .latestOrderId(snapshot.latestOrderId())
                .verifiedAt(snapshot.verifiedAt())
                .build();
    }

    private PlanStatusResponse post(String token, String productId) {
        return service.verify(caller, new PurchaseVerificationRequest(token, productId));
    }

    private SubscriptionWriter.Snapshot captureWrite() {
        ArgumentCaptor<SubscriptionWriter.Snapshot> captor =
                ArgumentCaptor.forClass(SubscriptionWriter.Snapshot.class);
        verify(writer).upsert(captor.capture());
        return captor.getValue();
    }

    private static void assertRefused(ThrowingCall call, HttpStatus status, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    assertThat(((ApiException) thrown).status()).isEqualTo(status);
                    assertThat(((ApiException) thrown).code()).isEqualTo(code);
                });
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }

    // ------------------------------------------------------------------------- the happy path

    @Test
    void aNewActivePurchaseIsRecordedAcknowledgedAndAnsweredWithTheFullPlanStatus() {
        PlanStatusResponse status = post("fake-active-pro", PRO);

        SubscriptionWriter.Snapshot written = captureWrite();
        assertThat(written.userId()).isEqualTo(caller);
        assertThat(written.tier()).isEqualTo(Plan.PRO);
        assertThat(written.productId()).isEqualTo(PRO);
        assertThat(written.provenance()).isEqualTo(PurchaseProvenance.PLAY_PURCHASE);
        assertThat(written.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(written.entitledUntil()).isAfter(Instant.now());
        assertThat(play.acknowledgedTokens()).containsExactly("fake-active-pro");
        verify(writer).markAcknowledged(any());
        // The body is the plan status, so the purchase result and the plan screen cannot disagree.
        assertThat(status).isSameAs(planLimits.statusOf(caller));
    }

    @Test
    void theStoredExpiryIsTheMatchedLineItemsAndTheHighWaterMarkIsNeverSeeded() {
        post("fake-active-max", MAX);

        SubscriptionWriter.Snapshot written = captureWrite();
        assertThat(written.entitledUntil())
                .isCloseTo(Instant.now().plus(Duration.ofDays(365)), within(Duration.ofMinutes(1)));
        // Snapshot has no lastEventTime component at all: verifying applies no RTDN, and seeding
        // the high-water mark with "now" would make the handler discard every notification already
        // in flight for this purchase.
        assertThat(SubscriptionWriter.Snapshot.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("lastEventTime");
    }

    private static org.assertj.core.data.TemporalUnitOffset within(Duration duration) {
        return org.assertj.core.api.Assertions.within(duration.toMillis(),
                java.time.temporal.ChronoUnit.MILLIS);
    }

    @Test
    void aPromotionIsRecordedAsPromoCodeAndIsDetectedFromTheLineItemNotAPrice() {
        post("fake-trial-standard", "whereis_standard_annual");

        assertThat(captureWrite().provenance()).isEqualTo(PurchaseProvenance.PROMO_CODE);
    }

    @Test
    void aTestPurchaseEntitlesButIsFlagged() {
        post("fake-test-pro", PRO);

        SubscriptionWriter.Snapshot written = captureWrite();
        assertThat(written.testPurchase()).isTrue();
        assertThat(written.tier()).isEqualTo(Plan.PRO);
    }

    @Test
    void anAlreadyAcknowledgedPurchaseIsNotAcknowledgedAgain() {
        post("fake-acked-pro", PRO);

        assertThat(play.acknowledgedTokens()).isEmpty();
        verify(writer, never()).markAcknowledged(any());
        assertThat(captureWrite().acknowledged()).isTrue();
    }

    @Test
    void aCanceledPurchaseWithAFutureTermStillEntitles() {
        post("fake-canceled-pro", PRO);

        SubscriptionWriter.Snapshot written = captureWrite();
        assertThat(written.state()).isEqualTo(SubscriptionState.CANCELED);
        assertThat(written.entitledUntil()).isAfter(Instant.now());
    }

    @Test
    void googlesLineItemDecidesTheTierEvenWhenTheClientClaimsAHigherProduct() {
        // A client claiming whereis_max_annual for a Standard purchase gets Standard — the answer
        // is never taken from the request.
        post("fake-active-standard", MAX);

        assertThat(captureWrite().tier()).isEqualTo(Plan.STANDARD);
        assertThat(captureWrite().productId()).isEqualTo("whereis_standard_annual");
    }

    // ------------------------------------------------------------------------ the failure table

    @Test
    void anUnconfiguredProductIdIsRefusedWithoutEvenAskingGoogle() {
        assertRefused(() -> post("fake-active-pro", "whereis_platinum_annual"),
                HttpStatus.BAD_REQUEST, ErrorCode.PLAY_PRODUCT_UNKNOWN);

        verifyNoInteractions(writer);
        assertThat(play.acknowledgedTokens()).isEmpty();
    }

    @Test
    void aTokenAlreadyLinkedToAnotherAccountIsRefusedWithoutAskingGoogle() {
        when(subscriptions.findByPurchaseToken("fake-active-pro"))
                .thenReturn(Optional.of(storedRow(otherUser, SubscriptionState.ACTIVE,
                        Instant.now().plus(Duration.ofDays(30)), Instant.now(), true)));

        assertRefused(() -> post("fake-active-pro", PRO),
                HttpStatus.CONFLICT, ErrorCode.PLAN_PURCHASE_NOT_OWNED);

        verifyNoInteractions(writer);
    }

    @Test
    void googlesObfuscatedAccountIdNamingAnotherUserIsRefusedAndNothingIsWritten() {
        String otherHash = PlayAccountHash.of(otherUser);

        assertRefused(() -> post("fake-active-pro@" + otherHash, PRO),
                HttpStatus.CONFLICT, ErrorCode.PLAN_PURCHASE_NOT_OWNED);

        verifyNoInteractions(writer);
    }

    @Test
    void googlesObfuscatedAccountIdNamingTheCallerIsAccepted() {
        post("fake-active-pro@" + PlayAccountHash.of(caller), PRO);

        assertThat(captureWrite().tier()).isEqualTo(Plan.PRO);
    }

    @Test
    void anAbsentObfuscatedAccountIdIsAcceptedBecauseAPromoRedemptionCarriesNone() {
        post("fake-active-pro", PRO);

        assertThat(captureWrite().userId()).isEqualTo(caller);
    }

    @Test
    void aPurchaseWhoseProductsAreNoneOfOursIsAProductMismatch() {
        assertRefused(() -> post("fake-foreignproduct-pro", PRO),
                HttpStatus.BAD_REQUEST, ErrorCode.PLAY_PRODUCT_MISMATCH);

        verifyNoInteractions(writer);
    }

    @Test
    void aTokenGoogleDoesNotKnowIsA400AndATransportFailureIsA502() {
        assertRefused(() -> post("not-a-google-token", PRO),
                HttpStatus.BAD_REQUEST, ErrorCode.PLAY_PURCHASE_INVALID);
        assertRefused(() -> post("fake-outage-pro", PRO),
                HttpStatus.BAD_GATEWAY, ErrorCode.PLAY_UNAVAILABLE);

        // Nothing written on either: the row only exists once Google has answered about it.
        verifyNoInteractions(writer);
    }

    @Test
    void everyNonEntitlingStateIsA409AndKeepsTheRowWithItsTrueState() {
        for (String token : java.util.List.of("fake-expired-pro", "fake-paused-pro",
                "fake-onhold-pro", "fake-weirdstate-pro")) {
            SubscriptionWriter freshWriter = mock(SubscriptionWriter.class);
            when(freshWriter.upsert(any())).thenAnswer(invocation -> rowOf(invocation.getArgument(0)));
            PurchaseVerificationService fresh = new PurchaseVerificationService(
                    subscriptions, freshWriter, play, properties(PlayProperties.FAKE), catalog, planLimits,
                    new SubscriptionLinkResolver(subscriptions, freshWriter));

            assertRefused(() -> fresh.verify(caller, new PurchaseVerificationRequest(token, PRO)),
                    HttpStatus.CONFLICT, ErrorCode.PLAY_PURCHASE_NOT_ACTIVE);

            ArgumentCaptor<SubscriptionWriter.Snapshot> captor =
                    ArgumentCaptor.forClass(SubscriptionWriter.Snapshot.class);
            verify(freshWriter).upsert(captor.capture());
            // The row is kept deliberately: the next wave's RECOVERED / RESTARTED notification
            // needs something to update, and a re-POST then becomes a no-op.
            assertThat(captor.getValue().acknowledged()).isFalse();
            assertThat(captor.getValue().state().entitles()).isFalse();
            verify(freshWriter, never()).markAcknowledged(any());
        }
        assertThat(play.acknowledgedTokens()).isEmpty();
    }

    @Test
    void aPendingPurchaseWithNoExpiryIsRefusedAndStoredAlreadyNonEntitling() {
        assertRefused(() -> post("fake-pending-pro", PRO),
                HttpStatus.CONFLICT, ErrorCode.PLAY_PURCHASE_NOT_ACTIVE);

        // Google gave no expiry, so the writer stores the instant it asked: truthful, already in
        // the past, and therefore already non-entitling.
        assertThat(captureWrite().entitledUntil()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void anEntitlingStateWhoseTermIsAlreadyOverIsAlsoNotActive() {
        assertRefused(() -> post("fake-expired-max", MAX),
                HttpStatus.CONFLICT, ErrorCode.PLAY_PURCHASE_NOT_ACTIVE);
    }

    @Test
    void aFailedAcknowledgementIsRetriedThenLoggedWithoutFailingTheRequest() {
        PlanStatusResponse status = post("fake-ackfails-pro", PRO);

        // The entitlement is already committed and correct; a 502 here would hide a successful
        // purchase behind an error screen and make the client re-POST, which cannot help on its own.
        assertThat(status).isNotNull();
        assertThat(captureWrite().acknowledged()).isFalse();
        verify(writer, never()).markAcknowledged(any());
    }

    // ------------------------------------------------------------- idempotency and the 60s window

    @Test
    void aReplayedTokenInsideTheQuotaWindowIsAnsweredFromTheStoredRowWithNoGoogleCall() {
        when(subscriptions.findByPurchaseToken("fake-active-pro")).thenReturn(Optional.of(
                storedRow(caller, SubscriptionState.ACTIVE, Instant.now().plus(Duration.ofDays(30)),
                        Instant.now().minusSeconds(5), true)));

        PlanStatusResponse status = post("fake-active-pro", PRO);

        assertThat(status).isNotNull();
        verifyNoInteractions(writer);
        // No Google call at all — that is the point of the window, and it is what caps a looping
        // client at one Play API call per token per minute.
        assertThat(play.acknowledgedTokens()).isEmpty();
    }

    @Test
    void aNonEntitlingCachedRowReplaysIts409RatherThanAnswering200() {
        // The verdict is a function of the purchase's state, not of how recently we were asked. A
        // PENDING payment answered 409 and then 200 ten seconds later would make the client record
        // success and abandon the retry the 409 asked for.
        when(subscriptions.findByPurchaseToken("fake-pending-pro")).thenReturn(Optional.of(
                storedRow(caller, SubscriptionState.PENDING, Instant.now().minusSeconds(1),
                        Instant.now().minusSeconds(5), false)));

        assertRefused(() -> post("fake-pending-pro", PRO),
                HttpStatus.CONFLICT, ErrorCode.PLAY_PURCHASE_NOT_ACTIVE);

        verifyNoInteractions(writer);
    }

    @Test
    void anEntitlingButUnacknowledgedRowAlwaysReVerifiesSoTheAcknowledgementIsRepaired() {
        // THE INTERIM SUBSTITUTE FOR THE RECONCILER. Nothing else in this wave retries an
        // acknowledgement, and Google auto-refunds after 3 days — 5 MINUTES for a test purchase —
        // so this case must never short-circuit, however recently it was verified.
        when(subscriptions.findByPurchaseToken("fake-active-pro")).thenReturn(Optional.of(
                storedRow(caller, SubscriptionState.ACTIVE, Instant.now().plus(Duration.ofDays(30)),
                        Instant.now().minusSeconds(1), false)));

        post("fake-active-pro", PRO);

        assertThat(play.acknowledgedTokens()).containsExactly("fake-active-pro");
        verify(writer).markAcknowledged(any());
    }

    @Test
    void aStaleKnownTokenIsReVerifiedAndRefreshed() {
        when(subscriptions.findByPurchaseToken("fake-active-pro")).thenReturn(Optional.of(
                storedRow(caller, SubscriptionState.ACTIVE, Instant.now().plus(Duration.ofDays(30)),
                        Instant.now().minus(Duration.ofHours(2)), true)));

        post("fake-active-pro", PRO);

        assertThat(captureWrite().verifiedAt()).isAfter(Instant.now().minusSeconds(30));
    }

    @Test
    void losingTheInsertRaceRepairsTheRowInAFreshTransaction() {
        // doThrow, not when(...): the mock is already stubbed with an answer, so when(writer.upsert(any()))
        // would actually invoke it with a null snapshot.
        doThrow(new DataIntegrityViolationException("duplicate key")).when(writer).upsert(any());
        doAnswer(invocation -> rowOf(invocation.getArgument(0)))
                .when(writer).refreshOwned(any(), eq(caller));

        PlanStatusResponse status = post("fake-active-pro", PRO);

        assertThat(status).isNotNull();
        // The catch lives OUTSIDE the writer's transaction, which is rollback-only by then, and the
        // repair re-asserts ownership before touching the winner's row.
        verify(writer).refreshOwned(any(), eq(caller));
    }

    @Test
    void losingTheRaceToAnotherAccountIsA409RatherThanASilent200() {
        doThrow(new DataIntegrityViolationException("duplicate key")).when(writer).upsert(any());
        doThrow(new PlanPurchaseNotOwnedException("This purchase belongs to another account"))
                .when(writer).refreshOwned(any(), eq(caller));

        assertRefused(() -> post("fake-active-pro", PRO),
                HttpStatus.CONFLICT, ErrorCode.PLAN_PURCHASE_NOT_OWNED);
    }

    @Test
    void theAcknowledgementIsAttemptedOnceOnSuccess() {
        post("fake-active-pro", PRO);

        assertThat(play.acknowledgedTokens()).hasSize(1);
        verify(writer, times(1)).markAcknowledged(any());
    }

    /**
     * The billing-not-configured mode, from the endpoint's side. Three assertions rather than one,
     * because "answers 501" is the least important of them: what makes this mode safe to run in
     * production is that the refusal happens before ANY read and ANY write, so there is no path
     * from a POST to a row.
     */
    @Test
    void aPurchaseIsRefusedWith501AndTouchesNothingWhenBillingIsNotConfigured() {
        PurchaseVerificationService off = new PurchaseVerificationService(subscriptions, writer,
                new DisabledPlaySubscriptionsApi(), properties(PlayProperties.DISABLED), catalog,
                planLimits, new SubscriptionLinkResolver(subscriptions, writer));

        assertRefused(() -> off.verify(caller, new PurchaseVerificationRequest("fake-active-pro", PRO)),
                HttpStatus.NOT_IMPLEMENTED, ErrorCode.PLAY_BILLING_NOT_CONFIGURED);

        // Nothing read, nothing written, no plan status composed: the whole flow is zero statements.
        verifyNoInteractions(subscriptions, writer, planLimits);
    }

    /**
     * The refusal is FIRST, ahead of the product-id check. A client that posted an unknown product
     * would otherwise be told 400 PLAY_PRODUCT_UNKNOWN — "drop the token" — when the truth is that
     * this server cannot look at any token at all, and a dropped token is a purchase the user
     * cannot get back.
     */
    @Test
    void theBillingRefusalWinsOverEveryOtherReasonThePostCouldBeRejected() {
        PurchaseVerificationService off = new PurchaseVerificationService(subscriptions, writer,
                new DisabledPlaySubscriptionsApi(), properties(PlayProperties.DISABLED), catalog,
                planLimits, new SubscriptionLinkResolver(subscriptions, writer));

        assertRefused(() -> off.verify(caller, new PurchaseVerificationRequest("anything", "not_a_product")),
                HttpStatus.NOT_IMPLEMENTED, ErrorCode.PLAY_BILLING_NOT_CONFIGURED);
    }

    private UserSubscription storedRow(UUID owner, SubscriptionState state, Instant entitledUntil,
                                       Instant verifiedAt, boolean acknowledged) {
        return UserSubscription.builder()
                .id(UUID.randomUUID())
                .userId(owner)
                .purchaseToken("stored")
                .productId(PRO)
                .tier(Plan.PRO)
                .provenance(PurchaseProvenance.PLAY_PURCHASE)
                .state(state)
                .entitledUntil(entitledUntil)
                .acknowledged(acknowledged)
                .verifiedAt(verifiedAt)
                .build();
    }
}
