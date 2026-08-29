package az.technest.whereis.assistant;

import az.technest.whereis.assistant.mock.MockAiAssistant;
import az.technest.whereis.assistant.openai.OpenAiAssistant;
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
    public AiAssistant aiAssistant(AiProperties properties, ObjectMapper objectMapper) {
        return switch (properties.provider()) {
            case "mock" -> new MockAiAssistant();
            case "openai" -> new OpenAiAssistant(aiRestClient(properties), properties, objectMapper);
            default -> throw new IllegalStateException(
                    "Unknown ai.provider '" + properties.provider() + "' (supported: mock, openai)");
        };
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
