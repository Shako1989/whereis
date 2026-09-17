package az.technest.whereis.assistant;

/**
 * Which provider, model and prompt produced an interpretation. Stored on every
 * {@code assistant_messages} row so a prompt change can later be measured against the sentences
 * it was applied to.
 *
 * @param provider      {@code mock}, {@code openai} or {@code claude}
 * @param model         the model id in force for this request (live configuration, never frozen)
 * @param promptVersion see {@link PromptVersion} — identifies the immutable prompt text only
 */
public record AiMetadata(String provider, String model, String promptVersion) {

    /**
     * No model was consulted. Used by the pinned-destination path (BR-7), where the destination is
     * already settled and the text is taken as the item name verbatim, so there is no provider, no
     * prompt and nothing to interpret. The three columns are NOT NULL, so a sentinel is needed
     * rather than nulls — and naming the configured provider here would be a lie, because nothing
     * was asked of it. A CREATED row with {@code provider = 'none'} and a NULL {@code
     * interpretation} is exactly the verbatim path; a NULL interpretation with a real provider is a
     * failure instead.
     */
    public static final AiMetadata NONE = new AiMetadata("none", "none", "none");
}
