package az.technest.whereis.location;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tree walks over the locations table. Deliberately ownership-agnostic:
 * callers must ownership-check the ids they pass in.
 */
@Repository
public class LocationTreeDao {

    private static final String PATHS_SQL = """
            WITH RECURSIVE walk AS (
                SELECT l.id AS start_id, l.space_id AS space_id, l.id, l.parent_location_id, l.name, 1 AS depth
                FROM locations l
                WHERE l.id IN (:ids)
              UNION ALL
                SELECT w.start_id, w.space_id, l.id, l.parent_location_id, l.name, w.depth + 1
                FROM locations l
                JOIN walk w ON l.id = w.parent_location_id
            )
            SELECT w.start_id, s.name AS space_name, array_agg(w.name ORDER BY w.depth DESC) AS path
            FROM walk w
            JOIN spaces s ON s.id = w.space_id
            GROUP BY w.start_id, s.name
            """;

    private static final String SELF_AND_ANCESTORS_SQL = """
            WITH RECURSIVE ancestors AS (
                SELECT l.id, l.parent_location_id, 1 AS depth
                FROM locations l
                WHERE l.id = :startId
              UNION ALL
                SELECT l.id, l.parent_location_id, a.depth + 1
                FROM locations l
                JOIN ancestors a ON l.id = a.parent_location_id
            )
            SELECT id FROM ancestors ORDER BY depth
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public LocationTreeDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Resolves full display paths (space name first, then root..leaf location names)
     * for a whole set of locations in one query — no per-row lookups.
     */
    public Map<UUID, List<String>> resolvePaths(Collection<UUID> locationIds) {
        if (locationIds == null || locationIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query(PATHS_SQL, Map.of("ids", locationIds), LocationTreeDao::extractPaths);
    }

    /** The chain [start, parent, ..., root]; used for cycle checks and depth caps. */
    public List<UUID> selfAndAncestors(UUID startId) {
        return jdbc.queryForList(SELF_AND_ANCESTORS_SQL, Map.of("startId", startId), UUID.class);
    }

    /**
     * Serializes structural tree changes (re-parent, delete, chain-create) per space.
     * Transaction-scoped advisory lock — must be called inside an active transaction.
     */
    public void lockSpace(UUID spaceId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))",
                Map.of("key", spaceId.toString()),
                rs -> null);
    }

    private static Map<UUID, List<String>> extractPaths(ResultSet rs) throws SQLException {
        Map<UUID, List<String>> result = new HashMap<>();
        while (rs.next()) {
            UUID startId = rs.getObject("start_id", UUID.class);
            String spaceName = rs.getString("space_name");
            String[] names = (String[]) rs.getArray("path").getArray();
            List<String> path = new ArrayList<>(names.length + 1);
            path.add(spaceName);
            path.addAll(Arrays.asList(names));
            result.put(startId, path);
        }
        return result;
    }
}
