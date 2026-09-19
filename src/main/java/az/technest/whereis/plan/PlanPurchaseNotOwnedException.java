package az.technest.whereis.plan;

import az.technest.whereis.common.error.ConflictException;
import az.technest.whereis.common.error.ErrorCode;

/**
 * This purchase belongs to another account. Raised by either of the two independent cross-account
 * defences: the token is already bound to a different {@code user_id} (which
 * {@code updatable = false} makes permanent), or Google's {@code obfuscatedExternalAccountId} names
 * a different account.
 *
 * <p>409 rather than 403: this API answers every guard violation with 409 and every ownership miss
 * with 404, the client branches on {@code code} rather than on the status, and a 403 here would be
 * the only 403 in the codebase.
 */
public class PlanPurchaseNotOwnedException extends ConflictException {

    public PlanPurchaseNotOwnedException(String message) {
        super(ErrorCode.PLAN_PURCHASE_NOT_OWNED, message);
    }
}
