package az.technest.whereis.plan.rtdn;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /play/rtdn} — the Cloud Pub/Sub push endpoint for Google Play real-time developer
 * notifications.
 *
 * <p><strong>Outside {@code /api/v1} on purpose.</strong> It is not part of the client contract, it
 * is not CORS-exposed ({@code CorsConfigurationSource} registers {@code /api/**} only), and putting
 * it under {@code /api/v1} invites somebody to reason about it as an API endpoint with a caller who
 * holds a JWT. It has no JWT and never will.
 *
 * <p><strong>THE HIGHEST-STAKES DECISION: how it authenticates.</strong> This endpoint can hand any
 * account any tier and it is reachable by anyone on the internet, so it gets TWO INDEPENDENT CHECKS
 * and both must pass.
 * <ol>
 *   <li><strong>A shared secret in the query string.</strong> The push URL registered on the
 *       subscription is {@code https://<host>/play/rtdn?key=<32 random bytes, base64url>}, compared
 *       with {@code MessageDigest.isEqual} over UTF-8 bytes — constant time, because
 *       {@code String.equals} on a secret is a timing oracle and costs nothing to avoid. A blank
 *       configured secret FAILS every request rather than skipping the check.</li>
 *   <li><strong>Google's OIDC push token</strong>, through {@link PlayPushAuthenticator}.</li>
 * </ol>
 *
 * <p><strong>Why both.</strong> The OIDC token is the real authentication — unguessable, rotating,
 * bound to one service account. But it depends on a reachable JWKS and on {@code aud} matching what
 * the Console actually sends, and a Pub/Sub subscription created WITHOUT the OIDC option sends no
 * {@code Authorization} header at all, which is a configuration mistake otherwise
 * indistinguishable from a working setup. The shared secret is independent of all of that and costs
 * one comparison. Conversely the secret sits in a URL, which is the thing reverse proxies log.
 * Requiring both means one mistake is not a breach. <strong>Operational consequence, and it is in
 * {@code deploy/README.md}: Caddy's access log for this path must strip the query string.</strong>
 *
 * <p><strong>Nothing is parsed before both checks pass.</strong> The handler takes the two values it
 * needs as a {@code @RequestParam(required = false)} and a {@code @RequestHeader(required = false)}
 * — neither of which can throw — and reads the body ITSELF, bounded, only afterwards. Binding a
 * {@code @RequestBody} DTO instead would run Jackson during argument resolution, before this method
 * body ever executed: an unauthenticated attacker would reach the parser, a missing {@code key}
 * would throw {@code MissingServletRequestParameterException}, and both would surface through
 * {@code GlobalExceptionHandler} as a structured 400 that confirms the endpoint exists and what
 * shape it wants — the opposite of the uniform opaque 401 this design is built around. The size cap
 * would also have been silently unenforced.
 *
 * <p>A rejected request gets <strong>401 with a zero-length body</strong>: no {@code ApiError}, no
 * code, no hint about which check failed. One WARN line carries the remote address and the failing
 * check, and never the presented token or secret. <strong>No ledger row is written</strong> — the
 * ledger is a record of what Google sent, and an unauthenticated body is not evidence of anything.
 *
 * <p>A 401 to a GENUINE Google push (our own misconfiguration) is a nack, so Pub/Sub retries with
 * backoff and the backlog survives until it is fixed. That is the correct failure direction, and it
 * is why an auth failure is not a 200.
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class RtdnController {

    /** Referenced by {@code PlayRtdnConfig}'s {@code @Order(0)} chain, so the two cannot drift. */
    public static final String PATH = "/play/rtdn";

    private final RtdnProperties properties;
    private final PlayPushAuthenticator authenticator;
    private final RtdnService service;

    @PostMapping(PATH)
    public ResponseEntity<Void> handle(@RequestParam(name = "key", required = false) String key,
                                       @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                       String authorization,
                                       HttpServletRequest request) {
        if (!Boolean.TRUE.equals(properties.enabled())) {
            // 503 is retried by Pub/Sub, which is what we want: a disabled endpoint should build a
            // backlog to process later, not silently discard a week of entitlement changes.
            return ResponseEntity.status(503).build();
        }
        if (!secretMatches(key)) {
            log.warn("Rejected an RTDN push from {}: the shared secret did not match", request.getRemoteAddr());
            return ResponseEntity.status(401).build();
        }
        if (!authenticator.isGenuine(stripBearer(authorization))) {
            log.warn("Rejected an RTDN push from {}: the push token did not verify", request.getRemoteAddr());
            return ResponseEntity.status(401).build();
        }

        byte[] body;
        try {
            body = readCapped(request);
        } catch (IOException | IllegalStateException unreadable) {
            // Over the cap, or the connection died mid-body. Retrying an oversized body cannot
            // help, and there is no messageId to write a ledger row under.
            log.warn("Discarding an RTDN push whose body could not be read: {}", unreadable.getMessage());
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.status(service.handle(body).status()).build();
    }

    /**
     * The LAST resort, and a controller-local one on purpose: a handler declared on the controller
     * takes precedence over {@code GlobalExceptionHandler}'s {@code @RestControllerAdvice}.
     *
     * <p>{@link RtdnService} already catches everything the flow can produce and records the ledger
     * outcome, so reaching this means something genuinely unforeseen. It still must not become a
     * 502/400/409 with an {@code ApiError} body: those are not in the ack table, Pub/Sub nacks them,
     * and the body would leak the shape of an internal error to an endpoint reachable by anyone.
     * 500 with an empty body is a retry, which is the right answer to "we do not know what
     * happened".
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Void> handleAnything(Throwable failure) {
        log.error("Unhandled failure on {}", PATH, failure);
        return ResponseEntity.status(500).build();
    }

    /**
     * Constant time, and blank rejects. {@code MessageDigest.isEqual} rather than
     * {@code String.equals} because the latter short-circuits on the first differing byte and so
     * leaks the secret's prefix to anyone willing to time a few thousand requests.
     */
    private boolean secretMatches(String presented) {
        byte[] expected = properties.sharedSecret().getBytes(StandardCharsets.UTF_8);
        if (expected.length == 0 || presented == null || presented.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }

    private static String stripBearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String trimmed = authorization.trim();
        return trimmed.regionMatches(true, 0, "Bearer ", 0, 7) ? trimmed.substring(7).trim() : trimmed;
    }

    /**
     * A bounded read. The cap is checked against {@code Content-Length} first (cheap, and what an
     * honest client sends) AND enforced while reading, because {@code Content-Length} is a claim
     * rather than a fact — a chunked body carries none at all.
     */
    private byte[] readCapped(HttpServletRequest request) throws IOException {
        int cap = properties.maxPayloadBytes();
        long declared = request.getContentLengthLong();
        if (declared > cap) {
            throw new IllegalStateException("the body declares " + declared + " bytes, over the "
                    + cap + "-byte cap");
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(cap + 1);
            if (body.length > cap) {
                throw new IllegalStateException("the body exceeds the " + cap + "-byte cap");
            }
            return body;
        }
    }
}
