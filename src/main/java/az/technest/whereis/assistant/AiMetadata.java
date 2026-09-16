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
}
