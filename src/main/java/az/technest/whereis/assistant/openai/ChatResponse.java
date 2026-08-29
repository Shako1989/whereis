package az.technest.whereis.assistant.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Minimal OpenAI chat-completions wire format. */
@JsonIgnoreProperties(ignoreUnknown = true)
record ChatResponse(List<Choice> choices) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String content) {
    }
}
