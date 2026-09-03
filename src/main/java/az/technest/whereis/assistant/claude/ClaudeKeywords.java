package az.technest.whereis.assistant.claude;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * The structured-output schema for the assistant search flow. Keywords only — the assistant
 * never lets a model author an answer; {@code AssistantService} composes the reply from the rows
 * the keywords retrieve.
 */
record ClaudeKeywords(

        @JsonPropertyDescription("""
                The words naming the object the person is looking for, lower case, singular nouns \
                preferred, at most 5 entries, each at most 50 characters and containing only \
                letters, digits and spaces. Keep the user's own words and script; never \
                translate. Empty list when the message names no object.""")
        List<String> keywords
) {
}
