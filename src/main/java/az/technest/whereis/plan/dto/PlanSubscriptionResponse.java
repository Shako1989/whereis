package az.technest.whereis.plan.dto;

import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.PlanCatalog;
import az.technest.whereis.plan.PurchaseProvenance;
import az.technest.whereis.plan.SubscriptionState;
import az.technest.whereis.plan.UserSubscription;
import java.time.Instant;

/**
 * The caller's live Play subscription, or absent when they have none.
 *
 * <p><strong>CONTRACT CHANGE, and it widens rather than narrows.</strong> Wave 1 documented this as
 * "non-null exactly when an ENTITLING row exists". It is now non-null whenever a LIVE row exists —
 * {@code UserSubscriptionRepository#manageableOf} — which additionally covers {@code ON_HOLD},
 * {@code PAUSED} and {@code PENDING}, the three states in which a subscription is not entitling and
 * the user most needs to be able to act on it. Reporting null for an ON_HOLD subscriber, as wave 1
 * did, takes away the only control that can fix their failed payment. The client's rule is
 * unchanged: show "Manage subscription" iff this object is non-null.
 *
 * <p><strong>{@code plan} is NOT derived from this object, in either direction.</strong> The badge
 * describes the ENTITLEMENT ({@code max(users.plan, best entitling row)}); this describes a
 * SUBSCRIPTION that may or may not currently entitle. An ON_HOLD PRO subscriber must read
 * {@code plan: "FREE"} and {@code subscription.tier: "PRO"} at the same time, on the same screen —
 * that pair is not a contradiction, it is the honest answer, and {@link #entitling} is what lets
 * the client phrase it.
 *
 * <p>DELIBERATELY NOT HERE: {@code inTrial}, {@code autoRenewing}, {@code formattedPrice},
 * {@code trialDays}. The first two are either unbacked by a column or derivable from
 * {@code state} ({@code CANCELED} already means "will not renew"), and inventing state the schema
 * does not carry is how a report starts lying. Prices and trial lengths may come ONLY from Play
 * {@code ProductDetails} on the device: a server-side price is wrong for most countries and is
 * grounds for store rejection, and the trial length must be read from the pricing phases, never
 * hardcoded anywhere.
 *
 * @param productId    the Play product, or {@code null} for a hand-written operator grant
 * @param tier         the tier this purchase bought, frozen at verification time
 * @param state        Google's state as of {@code verifiedAt}
 * @param entitledUntil when this row stops entitling if nothing renews it
 * @param provenance   how the row came to exist
 * @param acknowledged whether Google's acknowledgement succeeded. <strong>false means the
 *                     acknowledgement is still owed</strong> — Google auto-refunds and revokes an
 *                     unacknowledged purchase after 3 days (5 MINUTES for a test purchase). The
 *                     reconciler now retries it, so a client re-post is no longer the only repair,
 *                     but re-posting while this reads false is still the fastest one.
 * @param entitling    whether THIS row currently entitles. New in wave 2, and it exists for one
 *                     job: the difference between "you keep Pro until 14 March" and "Pro is
 *                     paused". Never use it to compute a tier — the client does not compute tiers
 * @param pendingProductId Google's product id for the DEFERRED change that takes effect at
 *                     {@code entitledUntil}, or null when nothing is pending
 * @param pendingTier  the tier behind {@code pendingProductId}, resolved at READ time through
 *                     {@link PlanCatalog#tierOf}. <strong>Null rather than wrong</strong> when this
 *                     deployment does not configure that id — the screen then degrades to "your
 *                     plan changes on &lt;date&gt;" instead of naming a tier it invented
 */
public record PlanSubscriptionResponse(String productId, Plan tier, SubscriptionState state,
                                       Instant entitledUntil, PurchaseProvenance provenance,
                                       boolean acknowledged, boolean entitling,
                                       String pendingProductId, Plan pendingTier) {

    /**
     * The only factory. It takes the catalog because {@code pendingTier} must be resolved at read
     * time — freezing it on the row is the exact thing V11's column comment forbids, and passing
     * the catalog into the DTO is how a stale tier would otherwise get in.
     */
    public static PlanSubscriptionResponse of(UserSubscription row, PlanCatalog catalog, Instant now) {
        String pending = row.getPendingProductId();
        return new PlanSubscriptionResponse(row.getProductId(), row.getTier(), row.getState(),
                row.getEntitledUntil(), row.getProvenance(), row.isAcknowledged(),
                row.entitlesAt(now), pending,
                pending == null ? null : catalog.tierOf(pending).orElse(null));
    }
}
