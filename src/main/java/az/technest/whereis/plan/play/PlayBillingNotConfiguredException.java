package az.technest.whereis.plan.play;

import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * This deployment has no Play Billing configuration at all —
 * {@code whereis.play.provider=disabled} — so Google was never asked and never will be until the
 * service account exists.
 *
 * <p><strong>501, and the status is the whole point.</strong> Every other {@code PLAY_*} error says
 * something about the purchase; this one says something about the SERVER, and the four alternatives
 * were each rejected for a reason the client would have to live with:
 * <ul>
 *   <li><strong>200</strong> — impossible: this response must never imply a grant, and the 200 body
 *       on this endpoint is a {@code PlanStatusResponse}, which is exactly a grant.</li>
 *   <li><strong>400 / 404</strong> — both tell the client the TOKEN or the ROUTE is wrong. A client
 *       that drops a purchase token on this answer has thrown away money the user spent, and the
 *       token is the only thing that can be redeemed once billing is switched on. Nothing here is
 *       the caller's fault.</li>
 *   <li><strong>502 {@code PLAY_UNAVAILABLE}</strong> — the closest existing code, and wrong in the
 *       way that costs most: it means "Google is having a moment, retry with backoff", so a client
 *       would poll an endpoint that cannot succeed until a human creates a Google Cloud project.</li>
 *   <li><strong>503</strong> — same retry semantics, plus it is the status the RTDN endpoint uses
 *       for "temporarily off, keep the backlog", which this is not.</li>
 * </ul>
 *
 * <p>501 is "the server does not support the functionality required to fulfil the request": true,
 * permanent until the deployment changes, not the caller's fault, and unambiguously not a grant.
 * It is also the status this API already uses for exactly this shape of answer — the assistant's
 * image analysis returns 501 {@code AI_NOT_IMPLEMENTED} on the providers that do not implement it,
 * and the Android client already renders a 501 as a disabled feature rather than as an error.
 *
 * <p><strong>The client contract: KEEP THE TOKEN, stop asking this session.</strong> Play will
 * still report the purchase from {@code queryPurchasesAsync} on the next foreground, and that is
 * when the re-post should happen — not in a backoff loop.
 */
public class PlayBillingNotConfiguredException extends ApiException {

    public PlayBillingNotConfiguredException(String message) {
        super(HttpStatus.NOT_IMPLEMENTED, ErrorCode.PLAY_BILLING_NOT_CONFIGURED, message);
    }
}
