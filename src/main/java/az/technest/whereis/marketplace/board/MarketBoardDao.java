package az.technest.whereis.marketplace.board;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The only query in this application an unauthenticated stranger can run.
 *
 * <p><strong>Its {@code FROM} clause is one table.</strong> Not {@code items}, not
 * {@code locations}, not {@code spaces}, not {@code users} — which is what turns "never publish the
 * internal location path" from a rule somebody has to remember into a query that cannot express the
 * leak: {@code locationPath} comes from {@code LocationTreeDao.resolvePaths}, and this DAO never
 * has a location id to resolve. {@code MarketplacePublicSurfaceArchTest} makes the same statement a
 * build failure.
 *
 * <p>V13 added the only other table this SQL may name: {@code blocked_sellers}, and ONLY inside a
 * {@code NOT EXISTS} that selects the constant 1. It is reachable at all because the seller-level
 * sanction has to be consulted on every anonymous request — and it is that table rather than
 * {@code users} precisely because this is the one query in the application a stranger can run. A
 * block recorded as a column on {@code users} would have put the table holding every e-mail address
 * and every bcrypt hash into this statement, one careless {@code SELECT u.*} away from the wire;
 * every column of {@code blocked_sellers} is an operator's own note about a sanction.
 *
 * <p>Deliberately NOT a "public mode" of {@code SearchDao}: that SQL welds {@code :userId} into
 * both its recursive CTE anchor and its main predicate, so a public variant would mean a userId
 * that is sometimes null in a query whose whole job is to scope by userId.
 *
 * <p>There is no {@code COUNT(*)} anywhere, on purpose. It would be the most expensive thing on the
 * endpoint, on every anonymous request, and the total tells a scraper exactly how complete their
 * mirror is. The page fetches {@code size + 1} rows and reports {@code hasMore} instead.
 */
@Repository
@RequiredArgsConstructor
public class MarketBoardDao {

    /**
     * <strong>The one visibility predicate, used by BOTH queries in this class.</strong> Its first
     * two clauses are {@code Listing#isPubliclyVisible()} and both partial indexes are built on
     * exactly them, so the planner never reads a hidden or ended row.
     *
     * <p>The third clause is the SELLER-level sanction (V13), and it is a clause here rather than a
     * flag on {@code listings} for three reasons:
     *
     * <ul>
     *   <li><strong>It cannot be raced.</strong> A publish that passed
     *       {@code ListingService}'s guard microseconds before the block committed inserts a fresh
     *       ACTIVE, un-hidden row — and this predicate excludes it on the very next request, while
     *       any denormalized flag would have been written before the block existed. The filter is
     *       the INVARIANT; the publish guard is the courtesy that gives the seller a sentence
     *       instead of a listing nobody can see.</li>
     *   <li><strong>A block is reversible and must therefore destroy nothing.</strong> Stamping
     *       {@code hidden_at} onto the seller's rows would be indistinguishable from the per-listing
     *       hides an operator may already have applied, so an unblock would either resurrect a
     *       listing that was killed on its own merits or leave one buried that was not.</li>
     *   <li>It is the shape V12 already chose over a denormalized copy of {@code items.archived},
     *       with the same reasoning: a copy of somebody else's mutable flag makes every future
     *       writer responsible for maintaining it.</li>
     * </ul>
     *
     * <p>{@code NOT EXISTS} rather than {@code NOT IN}: both are correct against a NOT NULL primary
     * key, but the three-valued-logic trap in {@code NOT IN} is one nullable column away at all
     * times, and the planner wants the anti-join either way.
     *
     * <p><strong>COST, measured on PostgreSQL 16 with 20,000 listings across 2,000 sellers.</strong>
     * {@code ix_listings_browse} and {@code ix_listings_browse_city} still serve the query
     * unchanged — their partial predicates are untouched, so they still supply both the filter and
     * the {@code created_at DESC} ordering — and the anti-join sits ABOVE the index scan as an Index
     * Only Scan on {@code blocked_sellers}' primary key with zero heap fetches. The first page goes
     * from 33 to 46 cached buffer hits; with the block list empty the planner drops the inner side
     * to a scan over zero rows. <strong>The deepest legal page (99 of 50) roughly DOUBLES</strong>,
     * 5,016 to 10,291 buffers, because the probe runs once per row EXAMINED and a deep {@code OFFSET}
     * examines 5,000 to return 51 — the shape {@code MAX_PAGE} already exists to bound, and the
     * reason a deeper board would want keyset pagination rather than a flag on {@code listings}. An
     * index predicate cannot reference another table, so no partial index could have absorbed this
     * clause. Full numbers are in V13's own comment.
     */
    private static final String VISIBLE = """
            l.status = 'ACTIVE' AND l.hidden_at IS NULL
                AND NOT EXISTS (SELECT 1 FROM blocked_sellers b WHERE b.user_id = l.user_id)
            """;

    private static final String COLUMNS = """
            l.id, l.title, l.description, l.price_amount, l.price_currency,
            l.city, l.contact_phone, l.cover_file_id, l.created_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** One row of the board, projected explicitly: you cannot serialize a column you never selected. */
    public record BoardRow(UUID id, String title, String description, BigDecimal price,
                           String currency, String city, String phone, UUID coverFileId,
                           Instant createdAt) {
    }

    /**
     * @param normalizedQuery already {@code Names.normalize}d, or null for the unfiltered board
     * @param normalizedCity  already {@code Names.normalize}d, or null for every city
     * @param limit           the page size PLUS ONE — the caller uses the extra row as `hasMore`
     */
    public List<BoardRow> browse(String normalizedQuery, String normalizedCity, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM listings l WHERE " + VISIBLE);
        if (normalizedQuery != null) {
            // Trigram similarity on the title, plus a substring match so a short exact word still
            // finds a long title. The description is matched by substring only: it is up to 4000
            // characters and a trigram index on it would be the largest in the schema.
            sql.append(" AND (l.title ILIKE :like OR l.description ILIKE :like)");
        }
        if (normalizedCity != null) {
            sql.append(" AND l.normalized_city = :city");
        }
        sql.append(" ORDER BY l.created_at DESC, l.id DESC LIMIT :limit OFFSET :offset");

        return jdbc.query(sql.toString(),
                Map.of(
                        "like", normalizedQuery == null ? "" : "%" + escapeLike(normalizedQuery) + "%",
                        "city", normalizedCity == null ? "" : normalizedCity,
                        "limit", limit,
                        "offset", offset),
                (rs, row) -> map(rs));
    }

    public java.util.Optional<BoardRow> findVisible(UUID id) {
        List<BoardRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM listings l WHERE " + VISIBLE + " AND l.id = :id",
                Map.of("id", id), (rs, row) -> map(rs));
        return rows.stream().findFirst();
    }

    private static BoardRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BoardRow(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                rs.getBigDecimal("price_amount"),
                rs.getString("price_currency"),
                rs.getString("city"),
                rs.getString("contact_phone"),
                rs.getObject("cover_file_id", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }

    /** The same escaping {@code SearchDao} applies, for the same reason. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
