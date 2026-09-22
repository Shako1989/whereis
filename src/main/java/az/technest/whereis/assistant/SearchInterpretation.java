package az.technest.whereis.assistant;

import java.util.List;

/**
 * Raw, untrusted AI search interpretation — never an answer, only two ways of pointing at rows.
 *
 * <p>{@code keywords} is the original contract: words pulled out of the sentence, which
 * {@code PostgresSearchService} then matches by trigram similarity. That is a LEXICAL match, so it
 * can only find an item whose stored text looks like what was typed.
 *
 * <p>{@code matches} is the semantic half, and it exists because the lexical half cannot answer
 * "where is the thing I drill holes with" when the item is stored as "Matkap". It holds names the
 * model picked OUT OF THE LIST IT WAS GIVEN — the caller's own item names. Nothing here is
 * trusted: every entry is resolved against the caller's own rows by normalized name, so an
 * invented name resolves to nothing rather than to somebody else's item.
 *
 * <p>Both may be empty. A provider that knows nothing of {@code matches} — or a JSON body that
 * omits the key, which is how {@code OpenAiAssistant} deserializes — leaves it null, and the
 * search falls back to the keyword path it has always used.
 */
public record SearchInterpretation(List<String> keywords, List<String> matches) {
}
