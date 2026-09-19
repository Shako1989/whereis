package az.technest.whereis.common.legal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The five facts the legal pages state that only the operator can supply.
 *
 * They are deployment configuration, not source: the repository is public, and an operator's
 * postal address committed to it is in the history for good, while a value in {@code deploy/.env}
 * is one restart away from being corrected. That also means changing the support address costs a
 * container restart rather than a rebuild, because the pages are rendered from these values at
 * startup instead of being shipped pre-filled.
 *
 * <p>The dev/test defaults live in {@code application.yml} and are deliberately unusable — the
 * {@code .invalid} TLD is reserved by RFC 2606 for exactly this — so a build that reaches a user
 * with them still in place is obvious rather than plausible. {@code application-prod.yml} declares
 * every one of them with no default, so the prod profile cannot start without real values.
 *
 * @param supportEmail        an address a user can actually write to; Google requires a working one
 * @param legalEntity         who publishes the app — a person's name or a company
 * @param legalAddress        the postal address; Play publishes it on the store listing anyway
 * @param effectiveDate       the date the notice takes effect, as it should read on the page
 * @param backupRetentionDays how long deleted data can survive in backups. MUST match the box: the
 *                            number is a promise to the user, and the only honest source for it is
 *                            the rotation the backup job actually performs
 */
@ConfigurationProperties(prefix = "whereis.legal")
public record LegalProperties(
        String supportEmail,
        String legalEntity,
        String legalAddress,
        String effectiveDate,
        String backupRetentionDays
) {
}
