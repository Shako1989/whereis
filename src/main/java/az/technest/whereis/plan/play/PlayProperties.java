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
 * @param provider          {@code fake} (default: deterministic, no key, no network) or
 *                          {@code google}
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

    static final String DEFAULT_PACKAGE_NAME = "az.technest.whereis";
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    public PlayProperties {
        provider = provider == null || provider.isBlank() ? FAKE : provider.trim().toLowerCase(java.util.Locale.ROOT);
        packageName = packageName == null || packageName.isBlank() ? DEFAULT_PACKAGE_NAME : packageName.trim();
        serviceAccountJson = serviceAccountJson == null || serviceAccountJson.isBlank()
                ? null : serviceAccountJson.trim();
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
    }
}
