package az.technest.whereis.assistant.claude;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Anthropic (Claude) provider. Deliberately a separate namespace from
 * {@code ai.*}: the shared keys carry OpenAI-shaped defaults (an OpenAI base URL, an OpenAI
 * model id), and inferring "did the operator mean this for Claude?" from those values would be
 * guesswork. Nothing here is validated at bind time — {@code ai.provider=mock} deployments must
 * boot with no Anthropic key at all; the required-key check lives in {@code AiConfig}'s
 * {@code claude} branch so it fires only when the provider is actually selected.
 */
@ConfigurationProperties("ai.claude")
public record ClaudeProperties(
        String apiKey,
        String baseUrl,
        String workspaceId,
        String model,
        Duration timeout,
        int maxOutputTokens,
        Double temperature,
        Integer maxRetries
) {

    /**
     * Claude Haiku 4.5 — chosen for this workload: single-sentence fact extraction into a fixed
     * schema, at $1/$5 per MTok. Note what this model does NOT support, because it shapes
     * {@code ClaudeAssistant}: {@code output_config.effort} errors, adaptive thinking does not
     * exist (it predates it), and its minimum cacheable prefix is 4096 tokens — far above our
     * system prompt, so a cache breakpoint would silently never cache. Raise this to
     * {@code claude-sonnet-5} via AI_CLAUDE_MODEL if confidence calibration disappoints — and
     * blank AI_CLAUDE_TEMPERATURE at the same time, because sampling parameters are rejected
     * with a 400 from Opus 4.7 onward (4.7, 4.8, Opus 5), on Sonnet 5 and on the Fable family.
     * Opus 4.6 and Sonnet 4.6 still accept them, as does Haiku 4.5.
     */
    public static final String DEFAULT_MODEL = "claude-haiku-4-5";

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;
    static final int DEFAULT_MAX_RETRIES = 1;
    static final double DEFAULT_TEMPERATURE = 0.0;

    public ClaudeProperties {
        apiKey = blankToNull(apiKey);
        // Left null for the real API: the SDK then applies https://api.anthropic.com itself.
        baseUrl = blankToNull(baseUrl);
        // Only an identity-linked key needs this: without it the API answers 400
        // "anthropic-workspace-id is required ...". Null means "omit the header", as with
        // temperature, which is what a workspace-scoped key wants.
        workspaceId = blankToNull(workspaceId);
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
        maxOutputTokens = maxOutputTokens <= 0 ? DEFAULT_MAX_OUTPUT_TOKENS : maxOutputTokens;
        // Boxed, and null means "omit temperature from the request entirely" — the only way to
        // run this provider on a model that rejects sampling parameters (Opus 4.7+, Sonnet 5,
        // Fable). An out-of-range or NaN value is a typo rather than a deliberate omission, so
        // it falls back to the deterministic default; the comparison is written positively so
        // NaN fails it and takes that branch.
        if (temperature != null) {
            boolean inRange = temperature >= 0.0 && temperature <= 1.0;
            temperature = inRange ? temperature : DEFAULT_TEMPERATURE;
        }
        // One retry, not the SDK's default of two: this sits on a synchronous user-facing
        // endpoint, so worst-case wall clock is ~2x timeout rather than ~3x.
        // Boxed so an unset property is distinguishable from a deliberate 0 (no retries).
        maxRetries = maxRetries == null || maxRetries < 0 ? DEFAULT_MAX_RETRIES : maxRetries;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
