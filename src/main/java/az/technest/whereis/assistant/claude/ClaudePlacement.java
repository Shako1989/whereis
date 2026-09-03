package az.technest.whereis.assistant.claude;

import az.technest.whereis.location.LocationType;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * The structured-output schema for the remember flow. The Anthropic SDK derives a JSON Schema
 * from this record and the API constrains the model to it, so shape is guaranteed by the
 * provider rather than by parsing — there is no prose preamble to strip and no markdown fence
 * to survive. {@link LocationType} is used as a real enum here on purpose: it lands in the
 * schema as an enum, so the model cannot emit an eleventh location type at all.
 *
 * <p>Two conventions worth knowing. First, absence is the empty string, not null: nullability in
 * a derived schema is the one thing that would differ across model families, and "" is
 * unambiguous everywhere ({@code ClaudeAssistant} maps it back to null). Second, the field names
 * and bounds here duplicate {@code InterpretationValidator}'s rules — that class remains the
 * single source of truth and still re-checks every value; these annotations only steer the model
 * so it produces something that survives it.
 */
record ClaudePlacement(

        @JsonPropertyDescription("""
                The physical object that was put somewhere, in the user's own words and script, \
                singular, capitalised as a person would write it. At most 120 characters. \
                Empty string when the message is not about putting an item somewhere.""")
        String itemName,

        @JsonPropertyDescription("""
                Extra detail about the item that is actually present in the message, such as \
                colour, brand or model. At most 2000 characters. Empty string when the message \
                adds no such detail.""")
        String itemDescription,

        @JsonPropertyDescription("""
                The overall place, and only when the message explicitly names one: home, office, \
                car, garage or warehouse. At most 80 characters. Empty string otherwise. \
                Never guess a space.""")
        String spaceName,

        @JsonPropertyDescription("""
                The containment chain, ordered outermost first and innermost last: the room, \
                then the piece of furniture, then the compartment inside it. At most 6 entries. \
                Empty list when the message is not about putting an item somewhere.""")
        List<ClaudeSegment> locations,

        @JsonPropertyDescription("""
                How certain you are, between 0 and 1, using the calibration bands in the \
                instructions. Never omit this field.""")
        double confidence
) {

    record ClaudeSegment(

            @JsonPropertyDescription("""
                    The name of this place in the user's own words and script, capitalised as a \
                    person would write it, at most 80 characters, using only letters, digits, \
                    spaces and these characters: . , ' & ( ) -""")
            String name,

            @JsonPropertyDescription("What kind of place this is. Use OTHER when none of the others fit.")
            LocationType type
    ) {
    }
}
