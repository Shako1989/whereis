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
        int maxOutputTokens
) {

    public AiProperties {
        provider = provider == null || provider.isBlank() ? "mock" : provider.trim().toLowerCase();
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(30) : timeout;
        maxOutputTokens = maxOutputTokens <= 0 ? 800 : maxOutputTokens;
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
