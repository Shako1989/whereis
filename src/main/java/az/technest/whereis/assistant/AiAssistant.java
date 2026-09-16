package az.technest.whereis.assistant;

import java.util.List;

/**
 * Provider-agnostic AI port. Implementations understand natural language;
 * they hold no business logic and have no data access. All output is untrusted
 * until validated, and the database remains the only source of truth.
 */
public interface AiAssistant {

    /**
     * @param message         the user's own sentence, already sanitized
     * @param knownSpaceNames the names of the spaces this user actually owns, so a provider can
     *                        match a foreign-language mention ("evdə") to an existing space
     *                        ("Home"). It is a HINT for matching only: whatever comes back is
     *                        still resolved against the database by normalized name, so a name
     *                        that is not in this list simply fails to resolve and no space is
     *                        ever created from it. Never contains ids.
     */
    PlacementInterpretation interpretPlacement(String message, List<String> knownSpaceNames);

    SearchInterpretation interpretSearch(String message);

    ImageAnalysis analyzeImage(byte[] content, String contentType);

    /**
     * Which provider, model and prompt this instance uses for the given flow, recorded on every
     * {@code assistant_messages} row. {@code promptVersion} identifies the IMMUTABLE instruction
     * text only — never a per-request assembly such as the appended list of the caller's space
     * names — so stored sentences can later be grouped by the prompt that interpreted them.
     * {@code model} is read from live configuration on each call, never frozen at class load.
     */
    AiMetadata metadata(AssistantMode mode);
}
