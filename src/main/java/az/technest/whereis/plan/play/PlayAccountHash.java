package az.technest.whereis.plan.play;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The value that links a Play purchase to a whereis account:
 * {@code BillingFlowParams.setObfuscatedAccountId(sha256Hex(userId))} on the device, recomputed
 * here from the JWT subject and compared.
 *
 * <p><strong>THE BYTES ARE THE CONTRACT.</strong> A canonicalisation slip on either side — an
 * uppercase UUID, stripped dashes, a hash over a different string form, UTF-16 instead of UTF-8 —
 * makes every purchase on every device fail the cross-check, so the definition is pinned here and
 * in a golden vector both repositories assert:
 *
 * <pre>
 *   input   the UUID's canonical toString(): 36 characters, lowercase, hyphenated
 *   bytes   US-ASCII / UTF-8 of that string (identical for this alphabet)
 *   digest  SHA-256
 *   output  lowercase hex, exactly 64 characters
 *
 *   THE GOLDEN VECTORS, asserted by PlayAccountHashTest here and to be copied verbatim into the
 *   Android repository's own test:
 *     00000000-0000-0000-0000-000000000000
 *       -> 12b9377cbe7e5c94e8a70d9d23929523d14afa954793130f8a3959c7b849aca8
 *     3f2504e0-4f89-41d3-9a0c-0305e82c3301
 *       -> 16362f566387b3cf5a6e92fb0a986c76ca20eb3a0c12cbdfbd0b29501e0c18df
 * </pre>
 *
 * <p>Comparison is case-insensitive on the hex, so a client that upper-cases its digest still
 * matches — that costs nothing and removes one whole class of "every purchase 409s forever".
 */
public final class PlayAccountHash {

    private PlayAccountHash() {
    }

    /** Lowercase SHA-256 hex of the UUID's canonical string form. Exactly 64 characters. */
    public static String of(UUID userId) {
        return of(userId.toString());
    }

    static String of(String canonicalUserId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalUserId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; unreachable.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Whether Google's obfuscated account id names this user. Absent (null/blank) is NOT a match. */
    public static boolean matches(String obfuscatedExternalAccountId, UUID userId) {
        return obfuscatedExternalAccountId != null
                && obfuscatedExternalAccountId.equalsIgnoreCase(of(userId));
    }
}
