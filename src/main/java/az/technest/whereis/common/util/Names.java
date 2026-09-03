package az.technest.whereis.common.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The single normalization used by every writer and every lookup (manual API, AI resolution, search).
 * Normalizing in more than one way breaks sibling-uniqueness and entity resolution.
 */
public final class Names {

    private Names() {
    }

    /** Trim, collapse whitespace, NFKC-normalize — preserves the user's casing for display. */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String collapsed = raw.trim().replaceAll("\\s+", " ");
        return Normalizer.normalize(collapsed, Normalizer.Form.NFKC);
    }

    /**
     * Canonical lookup and dedup key: {@link #clean(String)}, lower-cased with {@link Locale#ROOT},
     * then folded to ASCII letters. Folding matters because the SAME physical place is written many
     * ways — a user (or the AI) types "Şkaf", "şkaf" or "skaf"; "siyirmə" or "siyirme" — and the
     * key is what decides whether those are one location or several. Without the fold each spelling
     * becomes its own sibling. Only the KEY is folded; {@link #clean(String)} still preserves the
     * original letters for display, so nothing the user sees is flattened.
     */
    public static String normalize(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            return null;
        }
        return foldToAsciiLetters(cleaned.toLowerCase(Locale.ROOT));
    }

    /**
     * Strips diacritics for the dedup key. NFKD splits an accented letter into a base plus
     * combining marks, which are then dropped (this also removes the combining dot that
     * {@code toLowerCase(ROOT)} leaves behind for the Azerbaijani dotted-İ). Two Azerbaijani
     * letters do not decompose and are mapped explicitly: schwa (ə) and dotless-i (ı).
     */
    private static String foldToAsciiLetters(String lower) {
        String stripped = Normalizer.normalize(lower, Normalizer.Form.NFKD)
                .replaceAll("\\p{Mn}+", "");
        StringBuilder out = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            out.append(switch (c) {
                case 'ə' -> 'e';
                case 'ı' -> 'i';
                default -> c;
            });
        }
        return out.toString();
    }
}
