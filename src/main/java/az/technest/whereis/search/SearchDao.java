package az.technest.whereis.search;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SearchDao {

    public record SearchRow(UUID id, String name, UUID currentLocationId, Instant updatedAt) {
    }

    /**
     * Matches item name/description/category by trigram similarity or substring,
     * plus items stored inside any location whose own or space name matches —
     * including all descendant locations (a search for "wardrobe" finds items
     * in the drawers inside it). Always scoped to the owning user.
     */
    private static final String SEARCH_SQL = """
            WITH RECURSIVE matched_locations AS (
                SELECT l.id
                FROM locations l
                JOIN spaces s ON s.id = l.space_id
                WHERE s.user_id = :userId
                  AND (l.normalized_name ILIKE :like OR s.normalized_name ILIKE :like)
              UNION
                SELECT l.id
                FROM locations l
                JOIN matched_locations m ON l.parent_location_id = m.id
            )
            SELECT i.id, i.name, i.current_location_id, i.updated_at
            FROM items i
            WHERE i.user_id = :userId
              AND i.archived = false
              AND (
                  i.normalized_name % :q
                  OR i.normalized_name ILIKE :like
                  OR COALESCE(i.description, '') ILIKE :like
                  OR COALESCE(i.category, '') ILIKE :like
                  OR i.current_location_id IN (SELECT id FROM matched_locations)
              )
            ORDER BY GREATEST(
                    similarity(i.normalized_name, :q),
                    similarity(COALESCE(i.description, ''), :q),
                    similarity(COALESCE(i.category, ''), :q)
                ) DESC,
                i.updated_at DESC
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SearchDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SearchRow> search(UUID userId, String normalizedQuery, int limit) {
        Map<String, Object> params = Map.of(
                "userId", userId,
                "q", normalizedQuery,
                "like", "%" + escapeLike(normalizedQuery) + "%",
                "limit", limit);
        return jdbc.query(SEARCH_SQL, params, (rs, rowNum) -> new SearchRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getObject("current_location_id", UUID.class),
                rs.getTimestamp("updated_at").toInstant()));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
