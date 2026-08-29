package az.technest.whereis.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import az.technest.whereis.assistant.openai.OpenAiAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiAssistantTest {

    private MockRestServiceServer server;
    private OpenAiAssistant assistant;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties(
                "openai", "https://ai.example/v1", "test-key", "test-model", 0.0,
                Duration.ofSeconds(5), 800);
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.apiKey());
        server = MockRestServiceServer.bindTo(builder).build();
        assistant = new OpenAiAssistant(builder.build(), properties, new ObjectMapper());
    }

    private static String chatResponse(String innerJsonEscaped) {
        return """
                {"choices":[{"message":{"role":"assistant","content":"%s"}}]}
                """.formatted(innerJsonEscaped);
    }

    @Test
    void parsesWellFormedPlacementJson() {
        String content = "{\\\"itemName\\\":\\\"Passport\\\",\\\"spaceName\\\":\\\"Home\\\","
                + "\\\"locations\\\":[{\\\"name\\\":\\\"Bedroom\\\",\\\"type\\\":\\\"ROOM\\\"}],"
                + "\\\"confidence\\\":0.92}";
        server.expect(requestTo("https://ai.example/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withSuccess(chatResponse(content), MediaType.APPLICATION_JSON));

        PlacementInterpretation result = assistant.interpretPlacement("I put my passport in the bedroom");

        assertThat(result.itemName()).isEqualTo("Passport");
        assertThat(result.spaceName()).isEqualTo("Home");
        assertThat(result.locations()).hasSize(1);
        assertThat(result.confidence()).isEqualTo(0.92);
    }

    @Test
    void survivesMarkdownFencedJson() {
        String fenced = "```json\\n{\\\"keywords\\\":[\\\"passport\\\"]}\\n```";
        server.expect(requestTo("https://ai.example/v1/chat/completions"))
                .andRespond(withSuccess(chatResponse(fenced), MediaType.APPLICATION_JSON));

        assertThat(assistant.interpretSearch("where is my passport").keywords())
                .containsExactly("passport");
    }

    @Test
    void malformedContentBecomesAiAssistantException() {
        server.expect(requestTo("https://ai.example/v1/chat/completions"))
                .andRespond(withSuccess(chatResponse("this is not json at all"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> assistant.interpretPlacement("whatever"))
                .isInstanceOf(AiAssistantException.class);
    }

    @Test
    void emptyChoicesBecomesAiAssistantException() {
        server.expect(requestTo("https://ai.example/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> assistant.interpretSearch("whatever"))
                .isInstanceOf(AiAssistantException.class);
    }

    @Test
    void imageAnalysisIsExplicitlyNotImplemented() {
        assertThatThrownBy(() -> assistant.analyzeImage(new byte[]{1}, "image/jpeg"))
                .isInstanceOf(AiNotImplementedException.class);
    }

    @Test
    void stripFencesHandlesAllShapes() {
        assertThat(OpenAiAssistant.stripFences("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(OpenAiAssistant.stripFences("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(OpenAiAssistant.stripFences("```\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    }

    @Test
    void openAiProviderRequiresKeyModelAndBaseUrl() {
        assertThatThrownBy(() -> new AiProperties("openai", "https://ai.example/v1", "", "m", 0.0, null, 0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AiProperties("openai", null, "key", "m", 0.0, null, 0))
                .isInstanceOf(IllegalStateException.class);
    }
}
