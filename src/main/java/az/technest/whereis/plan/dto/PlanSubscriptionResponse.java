package az.technest.whereis.plan.dto;

import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.PurchaseProvenance;
import az.technest.whereis.plan.SubscriptionState;
import az.technest.whereis.plan.UserSubscription;
import java.time.Instant;

/**
 * The caller's best entitling subscription, or absent when they have none.
 *
 * <p>Present exactly when an entitling row exists — REGARDLESS of which side won
 * {@code max(grant, subscription)}. A paid subscription must always be manageable, even by an
 * account that also holds a higher operator grant, so the client's rule is the simple one: show
 * "Manage subscription" iff this object is non-null.
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
 *                     unacknowledged purchase after 3 days (5 MINUTES for a test purchase), and
 *                     until the reconciler ships the client re-posting the token is the repair, so
 *                     it must ignore its own 24-hour re-post rule while this reads false.
 */
public record PlanSubscriptionResponse(String productId, Plan tier, SubscriptionState state,
                                       Instant entitledUntil, PurchaseProvenance provenance,
                                       boolean acknowledged) {

    public static PlanSubscriptionResponse of(UserSubscription row) {
        return new PlanSubscriptionResponse(row.getProductId(), row.getTier(), row.getState(),
                row.getEntitledUntil(), row.getProvenance(), row.isAcknowledged());
    }
}
