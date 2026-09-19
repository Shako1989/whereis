package az.technest.whereis.plan.play;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Play Developer API port. Nothing here is validated at bind time — a
 * {@code fake} deployment must boot with no Google credentials at all, exactly as
 * {@code ClaudeProperties} binds on every boot regardless of {@code ai.provider}. The
 * required-credential check lives in {@code PlayConfig}'s {@code google} branch, so it fires only
 * when that provider is actually selected.
 *
 * @param provider          {@code fake} (default: deterministic, no key, no network),
 *                          {@code google}, or {@code disabled} — billing is not configured in this
 *                          deployment and no Google credential is needed or read
 * @param packageName       the app whose purchases this server may ask about. SERVER CONFIG, never
 *                          a request field, so a caller cannot make this server ask Google about a
 *                          different app's purchase
 * @param serviceAccountJson the service-account key CONTENTS, from the environment. Required only
 *                          when {@code provider=google}; nothing else in the process reads it
 * @param timeout           connect and read timeout for a single Google call
 */
@ConfigurationProperties("whereis.play")
public record PlayProperties(String provider, String packageName, String serviceAccountJson,
                             Duration timeout) {

    public static final String FAKE = "fake";
    public static final String GOOGLE = "google";

    /**
     * <strong>Billing is deliberately switched off</strong>, and unlike {@link #FAKE} this value is
     * PERMITTED under the {@code prod} profile: it needs no Google credentials, refuses every
     * purchase with 501, and cannot grant a tier by any route, which makes it strictly safer than
     * either of the other two rather than a way around them. It exists because the free tier, the
     * plan endpoint and the 409 wall are live features that must keep serving while the Play
     * service account and the Pub/Sub topic do not yet exist.
     */
    public static final String DISABLED = "disabled";

    static final String DEFAULT_PACKAGE_NAME = "az.technest.whereis";
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    public PlayProperties {
        provider = provider == null || provider.isBlank() ? FAKE : provider.trim().toLowerCase(java.util.Locale.ROOT);
        packageName = packageName == null || packageName.isBlank() ? DEFAULT_PACKAGE_NAME : packageName.trim();
        serviceAccountJson = serviceAccountJson == null || serviceAccountJson.isBlank()
                ? null : serviceAccountJson.trim();
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
    }

    /**
     * Whether a Play call is possible at all in this deployment. <strong>The ONE reading of
     * {@link #DISABLED}</strong>, so the verify endpoint and the three scheduled billing jobs
     * cannot disagree about what "billing is off" means — and so the normalisation above (trim,
     * lowercase) is applied once rather than re-implemented by every caller.
     */
    public boolean billingConfigured() {
        return !DISABLED.equals(provider);
    }
}
