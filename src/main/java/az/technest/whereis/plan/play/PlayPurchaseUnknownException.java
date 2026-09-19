package az.technest.whereis.plan.play;

/**
 * Google answered <strong>404</strong>: it has never heard of this purchase token, or the token is
 * not a purchase of this package. A DEFINITIVE answer — no retry can change it.
 *
 * <p>A subclass of {@link PlayPurchaseInvalidException} rather than a sibling, so every existing
 * caller that only cares about "the client must drop this token" is unchanged, including the wire
 * contract ({@code 400 PLAY_PURCHASE_INVALID}).
 *
 * <p>The split exists because two callers must tell 404 from 400 and V10's single mapping could
 * not:
 * <ul>
 *   <li>{@code PlayCancellationJanitor} deletes a queue row on a 404 (there is nothing left to
 *       cancel) but must NOT delete it on a 400 — {@code purchases.subscriptions.cancel} is the v1
 *       endpoint and takes OUR stored product id, which can legitimately disagree with the token's
 *       current product after a re-pointed {@code whereis.plans.*.product-id} or a multi-line-item
 *       purchase. Discarding the cancellation there would leave Google auto-renewing a subscription
 *       whose account no longer exists, indefinitely.</li>
 *   <li>The RTDN handler acks a 404 as {@code IGNORED} (definitive, retrying cannot help) but
 *       treats a 400 as retryable-with-a-ceiling, because a 400 can be our own bug and a message
 *       marked processed advances the token's watermark irreversibly.</li>
 * </ul>
 *
 * <p>Neither ever REVOKES on the strength of a Google error. That rule is unchanged.
 */
public class PlayPurchaseUnknownException extends PlayPurchaseInvalidException {

    public PlayPurchaseUnknownException(String message) {
        super(message);
    }

    public PlayPurchaseUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
