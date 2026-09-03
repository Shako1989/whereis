package az.technest.whereis.assistant;

import az.technest.whereis.assistant.claude.ClaudeAssistant;
import az.technest.whereis.assistant.claude.ClaudeProperties;
import az.technest.whereis.assistant.mock.MockAiAssistant;
import az.technest.whereis.assistant.openai.OpenAiAssistant;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AiConfig {

    @Bean
    public AiAssistant aiAssistant(AiProperties properties, ClaudeProperties claudeProperties,
            ObjectMapper objectMapper) {
        return switch (properties.provider()) {
            case "mock" -> new MockAiAssistant();
            case "openai" -> new OpenAiAssistant(aiRestClient(properties), properties, objectMapper);
            case "claude" -> new ClaudeAssistant(anthropicClient(claudeProperties), claudeProperties);
            default -> throw new IllegalStateException(
                    "Unknown ai.provider '" + properties.provider() + "' (supported: mock, openai, claude)");
        };
    }

    /**
     * Built here rather than as a bean, and only inside the {@code claude} branch, so a
     * {@code mock} deployment still needs no Anthropic key. This is also where the required-key
     * check lives: {@link ClaudeProperties} binds without validating, because it binds on every
     * boot regardless of which provider is selected.
     */
    private AnthropicClient anthropicClient(ClaudeProperties properties) {
        if (properties.apiKey() == null) {
            throw new IllegalStateException(
                    "ai.claude.api-key (AI_CLAUDE_API_KEY) must be configured when ai.provider=claude");
        }
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .timeout(properties.timeout())
                .maxRetries(properties.maxRetries());
        if (properties.baseUrl() != null) {
            builder.baseUrl(properties.baseUrl());
        }
        // Identity-linked API keys are rejected with a 400 unless the request names the workspace
        // it acts in. The SDK has no typed setter for it, so it goes on as a plain header.
        if (properties.workspaceId() != null) {
            builder.putHeader("anthropic-workspace-id", properties.workspaceId());
        }
        return builder.build();
    }

    private RestClient aiRestClient(AiProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(properties.timeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
