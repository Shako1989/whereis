package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The five early returns of the upgrade/downgrade link resolver, each of which closes a different
 * way this goes wrong.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SubscriptionLinkResolverTest {

    private UserSubscriptionRepository subscriptions;
    private SubscriptionWriter writer;
    private SubscriptionLinkResolver resolver;

    private final UUID owner = UUID.randomUUID();
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        subscriptions = mock(UserSubscriptionRepository.class);
        writer = mock(SubscriptionWriter.class);
        resolver = new SubscriptionLinkResolver(subscriptions, writer);
        when(writer.markSuperseded(any(), any())).thenReturn(true);
    }

    @Test
    void anOrdinaryPurchaseWithNoLinkedTokenResolvesNothing() {
        assertThat(resolver.resolve(row(null, SubscriptionState.ACTIVE, null), now)).isFalse();
        verify(writer, never()).markSuperseded(any(), any());
    }

    @Test
    void aNewRowThatDoesNotYetEntitleSupersedesNothing() {
        // A deferred-payment purchase can be PENDING while the old one is still ACTIVE. Superseding
        // then would strip a user of entitlement they have ALREADY PAID FOR. The next notification,
        // or the reconciler, retries.
        UserSubscription pending = row("old-token", SubscriptionState.PENDING, null);

        assertThat(resolver.resolve(pending, now)).isFalse();
        verify(subscriptions, never()).findByUserIdAndPurchaseToken(any(), anyString());
        verify(writer, never()).markSuperseded(any(), any());
    }

    @Test
    void theLinkedTokenIsResolvedWithTheScopedFinderAndNeverTheGlobalOne() {
        // V10's HARD CONTRACT, not a style preference: superseded_by has a composite self-FK to
        // (id, user_id) because one Play account can be signed into two whereis accounts. Resolving
        // with the global findByPurchaseToken would produce a cross-user chain and a
        // user_subscriptions row that the Play-mandated DELETE /users/me cannot get past.
        UserSubscription newRow = row("old-token", SubscriptionState.ACTIVE, null);
        UserSubscription old = row(null, SubscriptionState.ACTIVE, null);
        when(subscriptions.findByUserIdAndPurchaseToken(owner, "old-token")).thenReturn(Optional.of(old));

        assertThat(resolver.resolve(newRow, now)).isTrue();

        verify(subscriptions).findByUserIdAndPurchaseToken(owner, "old-token");
        verify(subscriptions, never()).findByPurchaseToken(anyString());
        verify(writer).markSuperseded(old.getId(), newRow.getId());
    }

    @Test
    void aLinkedTokenThatBelongsToNobodyOfOursResolvesNothing() {
        UserSubscription newRow = row("old-token", SubscriptionState.ACTIVE, null);
        when(subscriptions.findByUserIdAndPurchaseToken(any(), anyString())).thenReturn(Optional.empty());

        assertThat(resolver.resolve(newRow, now)).isFalse();
        verify(writer, never()).markSuperseded(any(), any());
    }

    @Test
    void anAlreadySupersededRowIsNotSupersededTwice() {
        UserSubscription newRow = row("old-token", SubscriptionState.ACTIVE, null);
        UserSubscription old = row(null, SubscriptionState.ACTIVE, UUID.randomUUID());
        when(subscriptions.findByUserIdAndPurchaseToken(owner, "old-token")).thenReturn(Optional.of(old));

        assertThat(resolver.resolve(newRow, now)).isFalse();
        verify(writer, never()).markSuperseded(any(), any());
    }

    @Test
    void aRowWhoseLinkResolvesToItselfIsLeftAlone() {
        UserSubscription self = row("old-token", SubscriptionState.ACTIVE, null);
        when(subscriptions.findByUserIdAndPurchaseToken(owner, "old-token")).thenReturn(Optional.of(self));

        assertThat(resolver.resolve(self, now)).isFalse();
        verify(writer, never()).markSuperseded(any(), any());
    }

    @Test
    void aLostRaceOnTheUniqueSupersedesIndexIsSwallowedRatherThanPropagated() {
        // ux_user_subscriptions_supersedes is UNIQUE, so a concurrent writer CAN win. The catch is
        // outside the writer's transaction — the same discipline PurchaseVerificationService#persist
        // uses, because a constraint violation leaves the persistence context unusable.
        UserSubscription newRow = row("old-token", SubscriptionState.ACTIVE, null);
        UserSubscription old = row(null, SubscriptionState.ACTIVE, null);
        when(subscriptions.findByUserIdAndPurchaseToken(owner, "old-token")).thenReturn(Optional.of(old));
        when(writer.markSuperseded(any(), any())).thenThrow(new DataIntegrityViolationException("race"));

        assertThat(resolver.resolve(newRow, now)).isFalse();
    }

    private UserSubscription row(String linkedToken, SubscriptionState state, UUID supersededBy) {
        UserSubscription row = UserSubscription.builder()
                .id(UUID.randomUUID())
                .userId(owner)
                .purchaseToken("token-" + UUID.randomUUID())
                .productId("whereis_pro_annual")
                .tier(Plan.PRO)
                .provenance(PurchaseProvenance.PLAY_PURCHASE)
                .state(state)
                .entitledUntil(now.plus(Duration.ofDays(300)))
                .linkedPurchaseToken(linkedToken)
                .verifiedAt(now)
                .build();
        row.setSupersededBy(supersededBy);
        return row;
    }
}
