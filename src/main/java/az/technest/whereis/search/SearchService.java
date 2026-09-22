package az.technest.whereis.search;

import az.technest.whereis.search.dto.ItemSearchResult;
import java.util.List;
import java.util.UUID;

/**
 * Search port. The REST and assistant layers depend only on this interface,
 * so a semantic/pgvector implementation can be added later without touching the API.
 */
public interface SearchService {

    List<ItemSearchResult> search(UUID userId, String query, int limit);

    /**
     * Exactly the items whose normalized names are given, in the order given.
     *
     * <p>The lookup behind an AI-assisted search: the model is shown the caller's own item names
     * and picks from them, and this turns those picks back into rows. Unlike {@link #search} it
     * does no fuzzy matching at all — a name either is one of this user's active items or it is
     * nothing, which is what makes a model that invents a name harmless rather than dangerous.
     *
     * <p>Scoped by {@code userId} like everything else here, so a name belonging to somebody else
     * returns no row rather than theirs.
     *
     * <p>Order is the CALLER'S, because it is the model's ranking and SQL {@code IN} has no order
     * of its own. Names that match nothing are simply absent from the result.
     *
     * @param normalizedNames already through {@code Names.normalize} — this compares against
     *                        {@code items.normalized_name} and does not normalize again.
     */
    List<ItemSearchResult> findByNames(UUID userId, List<String> normalizedNames, int limit);
}
