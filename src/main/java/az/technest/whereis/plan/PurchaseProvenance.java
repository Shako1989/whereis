package az.technest.whereis.plan;

/**
 * How a {@code user_subscriptions} row came to exist. Pinned by
 * {@code ck_user_subscriptions_provenance} ({@code PurchaseProvenanceTest} parses V10).
 */
public enum PurchaseProvenance {

    /** An ordinary paid Play purchase. */
    PLAY_PURCHASE,

    /**
     * A Play promotional code redemption. It arrives as an ordinary purchase and is told apart ONLY
     * by the line item's {@code signupPromotion} — never by price, which reports the FULL amount
     * during a promo trial.
     */
    PROMO_CODE,

    /**
     * A hand-inserted, TIME-BOXED grant (deploy/README.md Step 10). Nothing in the application
     * writes this; {@code entitled_until} is what makes it time-boxed. Because
     * {@code ck_user_subscriptions_tier} forbids UNLIMITED, an OPERATOR row can grant STANDARD, PRO
     * or MAX only — an unlimited grant is permanent and lives on {@code users.plan}.
     */
    OPERATOR
}
