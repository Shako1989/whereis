package az.technest.whereis.marketplace;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The ONE place a raw client string is turned into a city code, for both callers that need it: the
 * seller's publish path and the anonymous board's filter.
 *
 * <p><strong>{@link Locale#ROOT} is not optional here, and these particular names are why.</strong>
 * Under an Azerbaijani or Turkish default locale {@code "imishli".toUpperCase()} is
 * {@code "İMİŞLİ"} and {@code "ISMAYILLI".toLowerCase()} is a dotless-ı string — neither
 * round-trips, so a code case-mapped with the default locale would stop matching the row it names
 * on a machine whose locale nobody thought about. The stored code stays byte-identical to what a
 * correct client sends.
 *
 * <p><strong>It answers "could this be a code", never "is this a city".</strong> Membership is the
 * database's answer — {@link MarketCityCatalog} for publishing, an equality predicate against
 * {@code market_cities} rows for the board — because a shape check that also claimed to know the
 * list would be a second copy of the list.
 *
 * <p>{@code Names.normalize} must never be applied to a code. It folds diacritics for user-typed
 * NAMES; a code has none to fold, and the fold would lower-case it into something no row matches.
 */
public final class MarketCityCodes {

    /** {@code market_cities.code} is {@code varchar(32)}; nothing longer can be one. */
    public static final int MAX_LENGTH = 32;

    /**
     * {@code ck_market_cities_code_shape}, in Java. Anything else — a label ("Bakı"), a phrase
     * ("28 May metro"), a probe ("' OR 1=1 --") or a megabyte of text — cannot be a code, so it is
     * rejected here without a statement rather than sent to PostgreSQL to match nothing.
     */
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z_]*[A-Z]");

    private MarketCityCodes() {
    }

    /**
     * The canonical form of a client-supplied code, or empty when the value cannot be one at all.
     *
     * <p>Lenient about surrounding whitespace and about case, so {@code ?city=baku} in a shared link
     * works; strict about everything else. It never throws, and it never returns a value that could
     * widen a query: an empty result means the caller must answer "nothing", never "everything".
     */
    public static Optional<String> canonical(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        return CODE.matcher(upper).matches() ? Optional.of(upper) : Optional.empty();
    }
}
