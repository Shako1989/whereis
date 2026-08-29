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
}
