package az.technest.whereis.marketplace;

/**
 * One value today, and stored on every row anyway. A price with no currency beside it is a number
 * whose meaning lives in a comment, and the day a second currency exists every pre-existing row is
 * ambiguous and cannot be backfilled with certainty.
 *
 * <p>Pinned to {@code ck_listings_price_currency}, which is written as a one-element {@code IN}
 * list rather than {@code = 'AZN'} so that one test helper reads every enum CHECK in this schema.
 * Widening this enum means widening that CHECK in a new migration.
 */
public enum ListingCurrency {
    AZN
}
