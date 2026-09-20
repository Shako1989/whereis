package az.technest.whereis.common.legal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The facts the legal pages state that only the operator can supply, plus the two retention
 * windows the pages promise and the code must honour.
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
 * @param cancellationRetryDays how long a purchase token may be kept after the account is gone,
 *                            while the Play cancellation is retried. The SAME value
 *                            {@code PlayCancellationJanitor} gives up after, bound here rather than
 *                            hardcoded in both places — {@code WHEREIS_LEGAL_BACKUP_RETENTION_DAYS}
 *                            exists for exactly this reason and the lesson transfers verbatim: the
 *                            constant and the page must change together or the page lies. Because
 *                            {@code LegalPages} refuses to boot on an unset marker, the cross-check
 *                            comes for free
 * @param billingLogRetentionDays how long a Google Play billing notification stays in the RTDN
 *                            ledger ({@code play_notifications}). The THIRD number on this record
 *                            that is a promise rather than a preference, and read by
 *                            {@code PlayNotificationJanitor} as its cutoff for the same reason the
 *                            two above are read by the backup job and the cancellation janitor: the
 *                            page and the sweep must move together or the page lies. It has a hard
 *                            FLOOR the janitor enforces at startup — Cloud Pub/Sub retains an
 *                            unacknowledged message for up to 7 days, and the ledger's primary key
 *                            is what makes a redelivery a no-op, so a window at or under 7 days
 *                            would trade a privacy promise for a double-applied notification
 */
@ConfigurationProperties(prefix = "whereis.legal")
public record LegalProperties(
        String supportEmail,
        String legalEntity,
        String legalAddress,
        String effectiveDate,
        String backupRetentionDays,
        String cancellationRetryDays,
        String billingLogRetentionDays
) {
}
