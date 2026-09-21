package az.technest.whereis.common.error;

import org.springframework.http.HttpStatus;

/**
 * 403, and the ONE thing it is for: a caller who is authenticated but is not an OPERATOR.
 *
 * <p><strong>This is not a hole in "ownership misses are 404, never 403".</strong> That rule
 * protects OWNED aggregates, where 403 would confirm that the id the caller guessed exists and
 * belongs to somebody. A moderation endpoint owns nothing and confirms nothing: the refusal is
 * about the CALLER's privilege and carries no information about any row. 404 was considered — it
 * would hide the moderation surface entirely — and rejected because the surface is not a secret
 * (the allowlist fails closed, so knowing the path buys nothing) while a 404 to a legitimate
 * operator with a mistyped allowlist entry is indistinguishable from a wrong URL.
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(ErrorCode code, String message) {
        super(HttpStatus.FORBIDDEN, code, message);
    }
}
