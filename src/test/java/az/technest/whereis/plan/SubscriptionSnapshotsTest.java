package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.plan.play.PlayLineItem;
import az.technest.whereis.plan.play.PlaySubscription;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The mapping the RTDN handler and the reconciler share, and the one decision inside it that a
 * review had to correct: <strong>a refresh that matches no offered line item keeps the row's frozen
 * tier instead of throwing.</strong>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SubscriptionSnapshotsTest {

    private final SubscriptionSnapshots snapshots = new SubscriptionSnapshots(catalog());
    private final Instant now = Instant.parse("2026-09-19T12:00:00Z");

    @Test
    void aRefreshThatMatchesNoOfferedProductKeepsTheFrozenTierAndProduct() {
        // SUBSCRIPTION_EXPIRED and SUBSCRIPTION_REVOKED are exactly when subscriptionsv2.get is
        // most likely to return no usable line item, and a re-pointed whereis.plans.*.product-id
        // does it for a LIVE row. Throwing PlayProductMismatchException here — which the first draft
        // did — produced a 400 the ledger never recorded and a reconciler batch that aborted on the
        // same row every fifteen minutes forever.
        UserSubscription row = row(Plan.PRO, "whereis_pro_annual");
        PlaySubscription google = google(SubscriptionState.EXPIRED,
                new PlayLineItem("com.example.retired", "annual", now.minusSeconds(60), false, false));

        SubscriptionWriter.Snapshot snapshot = snapshots.refreshOf(row, google, now);

        assertThat(snapshot.tier()).isEqualTo(Plan.PRO);
        assertThat(snapshot.productId()).isEqualTo("whereis_pro_annual");
        assertThat(snapshot.state()).isEqualTo(SubscriptionState.EXPIRED);
    }

    @Test
    void aRefreshWithNoLineItemsAtAllKeepsThePaidTermWhileTheStateStillEntitles() {
        // Zeroing the expiry because we could not READ a timestamp would end a subscription the
        // user paid for. The fail-closed direction here is to keep the term Google last told us
        // about, because Google is simultaneously saying the state still entitles.
        UserSubscription row = row(Plan.PRO, "whereis_pro_annual");
        Instant paidUntil = row.getEntitledUntil();

        SubscriptionWriter.Snapshot snapshot =
                snapshots.refreshOf(row, google(SubscriptionState.ACTIVE), now);

        assertThat(snapshot.entitledUntil()).isEqualTo(paidUntil);
    }

    @Test
    void aRefreshWithNoExpiryAndANonEntitlingStateStoresTheInstantWeAsked() {
        UserSubscription row = row(Plan.PRO, "whereis_pro_annual");

        SubscriptionWriter.Snapshot snapshot =
                snapshots.refreshOf(row, google(SubscriptionState.EXPIRED), now);

        assertThat(snapshot.entitledUntil()).isEqualTo(now);
    }

    @Test
    void theHighestTierLineItemWinsRatherThanTheFirstOneGoogleHappenedToList() {
        // Google does not promise the order of lineItems, so "first" would let the SAME purchase
        // record a different tier on a retry. Every candidate is a product the user genuinely
        // bought, so highest is also the right direction to err.
        UserSubscription row = row(Plan.STANDARD, "whereis_standard_annual");
        PlaySubscription google = google(SubscriptionState.ACTIVE,
                new PlayLineItem("whereis_standard_annual", "annual", now.plus(Duration.ofDays(10)), true, false),
                new PlayLineItem("whereis_max_annual", "annual", now.plus(Duration.ofDays(20)), true, false));

        SubscriptionWriter.Snapshot snapshot = snapshots.refreshOf(row, google, now);

        assertThat(snapshot.tier()).isEqualTo(Plan.MAX);
        // The MATCHED item's own expiry, never the aggregate.
        assertThat(snapshot.entitledUntil()).isEqualTo(now.plus(Duration.ofDays(20)));
    }

    @Test
    void aDeferredDowngradeIsCarriedOnPendingProductIdAsGooglesIdAndNotAsATier() {
        UserSubscription row = row(Plan.PRO, "whereis_pro_annual");
        PlaySubscription google = google(SubscriptionState.ACTIVE,
                new PlayLineItem("whereis_pro_annual", "annual", now.plus(Duration.ofDays(120)), true,
                        false, "whereis_standard_annual"));

        SubscriptionWriter.Snapshot snapshot = snapshots.refreshOf(row, google, now);

        // The CURRENT tier is still PRO — the downgrade has not been paid for and has not happened.
        assertThat(snapshot.tier()).isEqualTo(Plan.PRO);
        assertThat(snapshot.pendingProductId()).isEqualTo("whereis_standard_annual");
    }

    @Test
    void aPendingProductGoogleNoLongerReportsIsCleared() {
        // It is re-read on EVERY refresh, which is the difference from `tier`: a statement about a
        // future that has not been paid for is allowed to change.
        UserSubscription row = row(Plan.PRO, "whereis_pro_annual");
        row.setPendingProductId("whereis_standard_annual");
        PlaySubscription google = google(SubscriptionState.ACTIVE,
                new PlayLineItem("whereis_pro_annual", "annual", now.plus(Duration.ofDays(120)), true, false));

        assertThat(snapshots.refreshOf(row, google, now).pendingProductId()).isNull();
    }

    @Test
    void creationRefusesAPurchaseOfferingNoneOfOurProducts() {
        // There is no frozen tier to fall back on, and user_subscriptions.tier is NOT NULL, so this
        // purchase simply cannot be recorded. The RTDN attribution path answers IGNORED.
        Optional<SubscriptionWriter.Snapshot> snapshot = snapshots.creationOf(UUID.randomUUID(), "tok",
                google(SubscriptionState.ACTIVE,
                        new PlayLineItem("com.example.other", "annual", now.plusSeconds(60), true, false)),
                now);

        assertThat(snapshot).isEmpty();
    }

    @Test
    void creationWithNoExpiryStoresTheInstantWeAskedWhichIsAlreadyInThePast() {
        Optional<SubscriptionWriter.Snapshot> snapshot = snapshots.creationOf(UUID.randomUUID(), "tok",
                google(SubscriptionState.PENDING,
                        new PlayLineItem("whereis_pro_annual", "annual", null, false, false)),
                now);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().entitledUntil()).isEqualTo(now);
    }

    private static PlaySubscription google(SubscriptionState state, PlayLineItem... lineItems) {
        Instant expiry = List.of(lineItems).stream()
                .map(PlayLineItem::expiryTime)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        return new PlaySubscription(state, "SUBSCRIPTION_STATE_" + state, List.of(lineItems), null,
                expiry, null, true, false, "GPA.1", null);
    }

    private UserSubscription row(Plan tier, String productId) {
        return UserSubscription.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .purchaseToken("tok")
                .productId(productId)
                .tier(tier)
                .provenance(PurchaseProvenance.PLAY_PURCHASE)
                .state(SubscriptionState.ACTIVE)
                .entitledUntil(now.plus(Duration.ofDays(200)))
                .latestOrderId("GPA.old")
                .verifiedAt(now.minus(Duration.ofHours(20)))
                .build();
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
