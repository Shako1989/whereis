package az.technest.whereis.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one way a purchase token is allowed to appear in a log line.
 *
 * <p>A purchase token is a bearer credential: anyone holding it can ask Google about the purchase,
 * and §6 forbids logging it above DEBUG. But an incident needs to correlate one token across the
 * verify endpoint, the RTDN handler, the reconciler and the cancellation janitor, so "log nothing"
 * is not an option either. Twelve hex characters of SHA-256 is the compromise: enough to join log
 * lines, useless to anyone who intercepts it.
 *
 * <p>Lifted out of {@code PurchaseVerificationService} when the RTDN handler became the fourth
 * caller — four private copies of a hash is four chances for one of them to drift and stop
 * correlating with the other three.
 */
public final class PurchaseTokens {

    private PurchaseTokens() {
    }

    /** Twelve hex characters of SHA-256, or {@code "-"} for a null/blank token. */
    public static String digest(String token) {
        if (token == null || token.isBlank()) {
            return "-";
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
