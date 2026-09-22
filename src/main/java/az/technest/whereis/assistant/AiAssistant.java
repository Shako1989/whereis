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

    /**
     * @param message        the user's own sentence, already sanitized
     * @param knownItemNames the names of the ACTIVE items this user actually owns, so a provider
     *                       can map a description onto something they have registered — "divarda
     *                       deşik açan alət" onto "Matkap" — which trigram search cannot do,
     *                       because the two share no letters. Exactly the same kind of hint as
     *                       {@code knownSpaceNames}: whatever comes back is resolved against the
     *                       database by normalized name, so a name the model invents simply fails
     *                       to resolve and returns nothing. Never contains ids.
     *                       <p>THE CALLER HAS ALREADY BOUNDED THIS LIST (see
     *                       {@code ai.max-item-names}); an adapter sanitizes the entries it is
     *                       given but must not impose a count limit of its own, or the number
     *                       would have two owners that could disagree.
     *                       Empty means the caller chose not to offer a list — an inventory over
     *                       the cap, or the feature switched off — and the provider should then
     *                       return keywords only.
     */
    SearchInterpretation interpretSearch(String message, List<String> knownItemNames);

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
