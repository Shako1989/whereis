package az.technest.whereis.plan;

import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Stitches an upgrade or a downgrade together: when Google reports that a new purchase token
 * REPLACES an old one, point the old row at the new one so it stops entitling.
 *
 * <p><strong>The server never learns the replacement mode, and must not need to.</strong> The
 * client chooses one ({@code CHARGE_PRORATED_PRICE} for an upgrade, {@code DEFERRED} for a
 * downgrade); Google applies it; the server sees either a new token whose
 * {@code linkedPurchaseToken} names the old one, or the same token with a changed product at
 * renewal. Both are answered by the two rules the server already has — refresh from Google, then
 * resolve the link — so there is no branch anywhere in the backend on "was this an upgrade or a
 * downgrade", and no place for the client's intent and the server's belief to disagree.
 *
 * <p>Called from ALL THREE entry points — the verify endpoint after its upsert, the RTDN
 * {@code SUBSCRIPTION_PURCHASED} branch, and the reconciler — so an upgrade is stitched together
 * whichever of them sees it first, including one whose notification was lost entirely.
 *
 * <p>No {@code @Transactional} on this class: it calls {@code SubscriptionWriter} through the
 * proxy, so the write is its own transaction and the {@code DataIntegrityViolationException} a lost
 * race produces is caught OUT HERE, where the persistence context is still usable — the same
 * discipline {@code PurchaseVerificationService#persist} uses and for the same reason.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionLinkResolver {

    private final UserSubscriptionRepository subscriptions;
    private final SubscriptionWriter writer;

    /**
     * Five early returns, each closing a different way this goes wrong:
     * <ol>
     *   <li>no link — an ordinary purchase, nothing to resolve;</li>
     *   <li><strong>the new row does not yet entitle.</strong> A deferred-payment purchase can be
     *       PENDING while the old one is still ACTIVE; superseding then would strip a user of
     *       entitlement they have already paid for. Leave the old row alone — the next
     *       notification, or the reconciler, retries;</li>
     *   <li>the linked token is not one of THIS user's rows. Resolved with the SCOPED finder, which
     *       is V10's hard contract: {@code linkedPurchaseToken} is scoped to a PLAY account, not a
     *       whereis account, and a cross-user chain would violate the composite self-FK and abort
     *       the single-transaction {@code AccountDeletionService} on the Play-mandated
     *       {@code DELETE /users/me};</li>
     *   <li>it resolves to the new row itself (a token that links to its own predecessor spelling);</li>
     *   <li>it is already superseded — {@code ux_user_subscriptions_supersedes} is UNIQUE.</li>
     * </ol>
     *
     * @return whether a supersession was written
     */
    public boolean resolve(UserSubscription newRow, Instant now) {
        String linked = newRow.getLinkedPurchaseToken();
        if (linked == null || linked.isBlank()) {
            return false;
        }
        if (!newRow.entitlesAt(now)) {
            return false;
        }
        Optional<UserSubscription> old =
                subscriptions.findByUserIdAndPurchaseToken(newRow.getUserId(), linked);
        if (old.isEmpty() || old.get().getId().equals(newRow.getId()) || old.get().getSupersededBy() != null) {
            return false;
        }
        try {
            boolean written = writer.markSuperseded(old.get().getId(), newRow.getId());
            if (written) {
                log.info("Purchase {} supersedes {} for user {}", PurchaseTokens.digest(newRow.getPurchaseToken()),
                        PurchaseTokens.digest(linked), newRow.getUserId());
            }
            return written;
        } catch (DataIntegrityViolationException race) {
            // A concurrent writer won ux_user_subscriptions_supersedes, or the composite self-FK
            // refused a cross-user link. Either way the answer is "somebody else already did it or
            // it was never legal", and INFO is the right level for both.
            log.info("Supersession of {} lost a race or was refused by the schema",
                    PurchaseTokens.digest(linked));
            return false;
        }
    }
}
