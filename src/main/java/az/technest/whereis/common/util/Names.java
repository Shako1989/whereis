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

    /** Canonical lookup key: {@link #clean(String)} lower-cased with {@link Locale#ROOT}. */
    public static String normalize(String raw) {
        String cleaned = clean(raw);
        return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
    }
}
