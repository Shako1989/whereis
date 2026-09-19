package az.technest.whereis.plan;

import az.technest.whereis.common.error.ConflictException;
import az.technest.whereis.common.error.ErrorCode;

/**
 * A creation refused because the account's plan has no room left. Answered <strong>409</strong>,
 * like every other guard violation in this API ({@code SPACE_NOT_EMPTY}, {@code DUPLICATE_NAME}) —
 * deliberately not 402: the client branches on {@code code}, not on the status.
 *
 * <p>The message names the TIER that was hit, the limit, and a call to action that must be true.
 * "Free plan limit reached" on a paying Standard account is a lie; "subscribe for unlimited spaces"
 * at MAX advertises a product that does not exist at any purchasable tier (MAX is ten spaces;
 * UNLIMITED is an operator grant and cannot be bought), which is a Play policy exposure as well as
 * a lie. So the upgrade half of the sentence is emitted only when a higher PURCHASABLE tier
 * actually raises THAT allowance — {@code PlanCatalog#aHigherTierRaisesSpaces} /
 * {@code #aHigherTierRaisesItems} answer that from configuration, so re-pointing the ladder
 * re-words the message with it.
 */
public class PlanLimitReachedException extends ConflictException {

    private PlanLimitReachedException(String message) {
        super(ErrorCode.PLAN_LIMIT_REACHED, message);
    }

    /**
     * @param limit            the space ceiling that was reached
     * @param tier             the caller's effective tier, named in the message
     * @param upgradeAvailable whether a higher purchasable tier actually allows more spaces
     */
    public static PlanLimitReachedException spaces(int limit, Plan tier, boolean upgradeAvailable) {
        String message = tier.displayName() + " plan limit reached: " + quantity(limit, "space", "spaces") + ".";
        return new PlanLimitReachedException(
                upgradeAvailable ? message + " Upgrade your plan for more spaces." : message);
    }

    /**
     * @param limit            the active-item ceiling that was reached
     * @param tier             the caller's effective tier, named in the message
     * @param upgradeAvailable whether a higher purchasable tier actually allows more items
     */
    public static PlanLimitReachedException activeItems(int limit, Plan tier, boolean upgradeAvailable) {
        // Archiving always frees room, on every tier, so that half of the sentence is unconditional.
        String message = tier.displayName() + " plan limit reached: "
                + quantity(limit, "active item", "active items")
                + ". Archive an item to free room";
        return new PlanLimitReachedException(
                upgradeAvailable ? message + ", or upgrade your plan for more items." : message + ".");
    }

    private static String quantity(int limit, String singular, String plural) {
        return limit + " " + (limit == 1 ? singular : plural);
    }
}
