package az.technest.whereis.assistant;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai")
public record AiProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        double temperature,
        Duration timeout,
        int maxOutputTokens,
        int maxItemNames
) {

    /**
     * The largest inventory whose item names are sent to the model on a search.
     *
     * <p>Chosen to cover every metered tier whole — free 100, standard 300, pro 600 — so the
     * feature only ever degrades for an unmetered account with a genuinely large inventory.
     */
    public static final int DEFAULT_MAX_ITEM_NAMES = 1000;

    public AiProperties {
        provider = provider == null || provider.isBlank() ? "mock" : provider.trim().toLowerCase();
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(30) : timeout;
        maxOutputTokens = maxOutputTokens <= 0 ? 800 : maxOutputTokens;
        // NOT normalized to a default when non-positive, unlike every value above it: zero is the
        // off switch. It makes assistant search send no item names at all and take the keyword
        // path it took before the feature existed — the shape whereis.play.provider=disabled and
        // minio.public-bucket already use, and the one lever for a deployment that judges the
        // token cost or the disclosure too high.
        maxItemNames = Math.max(maxItemNames, 0);
        if ("openai".equals(provider)) {
            requireForOpenAi(baseUrl, "ai.base-url (AI_BASE_URL)");
            requireForOpenAi(apiKey, "ai.api-key (AI_API_KEY)");
            requireForOpenAi(model, "ai.model (AI_MODEL)");
        }
    }

    private static void requireForOpenAi(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured when ai.provider=openai");
        }
    }
}
