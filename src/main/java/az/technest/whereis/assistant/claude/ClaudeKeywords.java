package az.technest.whereis.assistant.claude;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * The structured-output schema for the assistant search flow. Two ways of pointing at rows, and
 * neither is an answer — {@code AssistantService} composes the reply from the records that come
 * back, so a model still never authors a sentence the user reads.
 *
 * <p>{@code keywords} feeds the trigram search, which is lexical: it finds an item whose stored
 * text LOOKS like what was typed. {@code matches} is what makes a DESCRIPTION work — the model
 * picks from the caller's own item names, appended to the system prompt per request, so "the thing
 * I drill holes with" can reach an item stored as "Matkap" even though the two share no letters.
 *
 * <p>The fields carry opposite language rules on purpose, which is why the descriptions say so
 * twice: a keyword must stay in the user's own script because it is compared character by
 * character, while a match is chosen by meaning and therefore crosses languages freely.
 */
record ClaudeKeywords(

        @JsonPropertyDescription("""
                The words naming the object the person is looking for, lower case, singular nouns \
                preferred, at most 5 entries, each at most 50 characters and containing only \
                letters, digits and spaces. Keep the user's own words and script; never \
                translate. Empty list when the message names no object.""")
        List<String> keywords,

        @JsonPropertyDescription("""
                Names taken from the list of the user's own items given in the instructions, \
                copied EXACTLY as they appear there, ranked best first, at most 10 entries. \
                Include an item when it is what the person means even if they described it \
                instead of naming it, and even if their words are in another language than the \
                listed name. Choose only from that list: never invent a name, never return one \
                that is merely similar, and return an empty list when nothing listed is what \
                they mean or when no list was given.""")
        List<String> matches
) {
}
