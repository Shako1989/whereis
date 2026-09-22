package az.technest.whereis.marketplace;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The product numbers and shapes a listing must satisfy, in one place, because each of them exists
 * in TWO places otherwise — here and in a CHECK constraint — and two numbers for one fact drift.
 * {@code ListingDescriptionRuleTest} parses the migration and asserts they agree.
 */
public final class MarketplaceRules {

    /**
     * "Detailed" has to be a number or it cannot be enforced, tested or explained to a user.
     *
     * <p>40 characters is roughly six to eight words in Azerbaijani or English — enough to force a
     * noun phrase, a condition statement and one concrete detail. Ten to twenty would admit
     * {@code "təmiz, işlək"} / {@code "good condition"}, which is the entire thing this rule exists
     * to prevent; a hundred would block an honest short description of a simple object and teach
     * sellers to pad.
     *
     * <p><strong>Counted in CODE POINTS, not UTF-16 units.</strong> PostgreSQL's
     * {@code char_length} counts characters, so 39 astral-plane emoji are 39 to the database and
     * 78 to {@code String.length()} — a service using the wrong one accepts exactly what the CHECK
     * then refuses, and the user gets a 500 instead of a sentence.
     */
    public static final int MIN_DESCRIPTION_LENGTH = 40;

    public static final int MAX_DESCRIPTION_LENGTH = 4000;

    public static final int MIN_TITLE_LENGTH = 3;
    public static final int MAX_TITLE_LENGTH = 120;

    /** E.164: 15 digits is the ceiling, 7 a floor that excludes short codes. */
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{7,15}$");

    /** Everything that is not a digit or a leading plus, so "(050) 123-45-67" normalises cleanly. */
    private static final Pattern NOT_PHONE_CHARACTER = Pattern.compile("[^0-9+]");

    /**
     * Tokens that turn a free-text field into a phishing vector. An anonymous board is the perfect
     * place to plant a link and there is no accountable author, so the title and the description
     * refuse them outright rather than trying to render them safely. The city needs no such rule
     * since V15 — it is a {@code market_cities} code, so the only values it can hold are ones an
     * operator seeded.
     */
    private static final Pattern LINK_LIKE = Pattern.compile(
            "(?i)(https?://|www\\.|\\b[a-z0-9.-]+\\.(com|net|org|ru|az|info|biz|top|xyz)\\b|(^|\\s)@[a-z0-9_]{3,})");

    private MarketplaceRules() {
    }

    /** Counts what the database counts. */
    public static int lengthOf(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    public static boolean isLinkLike(String value) {
        return value != null && LINK_LIKE.matcher(value).find();
    }

    /**
     * Strips everything a phone number is not, keeping a single leading {@code +}. The value is
     * DIALLED, not read, so it is stored normalised rather than as typed — otherwise the same
     * number written three ways is three numbers to anyone trying to match them.
     */
    public static String normalizePhone(String raw) {
        if (raw == null) {
            return null;
        }
        boolean international = raw.trim().startsWith("+");
        String digits = NOT_PHONE_CHARACTER.matcher(raw).replaceAll("");
        digits = digits.replace("+", "");
        return international ? "+" + digits : digits;
    }

    public static boolean isPhone(String normalized) {
        return normalized != null && PHONE.matcher(normalized).matches();
    }

    /** Upper-cases for a message, not for storage. */
    public static String describe(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
