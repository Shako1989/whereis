package az.technest.whereis.marketplace.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * <strong>The public visibility rule, asserted against the SQL the board actually sends.</strong>
 *
 * <p>This is the one rule in the application whose failure mode is entirely silent: dropping a
 * clause does not fail a build, does not throw and does not log — it publishes a withdrawn listing,
 * or a listing an operator killed, or the listings of a seller who was blocked for fraud. The
 * three clauses are pinned here rather than by inspecting a constant, so that a query built without
 * them — a new "public search" method, a hand-assembled variant, a refactor that interpolates the
 * wrong string — is caught as well as an edit to the constant itself.
 *
 * <p>It also asserts what the SQL may NOT name. {@code users} in this statement would be the table
 * holding every e-mail address and every bcrypt hash, inside the only query an unauthenticated
 * stranger can run; {@code locations}, {@code spaces} and {@code items} are the three that could
 * carry the internal location path onto the wire.
 */
@ExtendWith(MockitoExtension.class)
class MarketBoardVisibilityTest {

    private static final String ACTIVE_ONLY = "l.status = 'ACTIVE'";
    private static final String NOT_KILLED = "l.hidden_at IS NULL";
    private static final String NOT_BLOCKED =
            "NOT EXISTS (SELECT 1 FROM blocked_sellers b WHERE b.user_id = l.user_id)";

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void bothPublicQueriesStateAllThreeVisibilityClauses() {
        // A RowMapper is unavoidably raw through Mockito's matcher API; the DAO's own mapper is
        // never invoked because no row comes back.
        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());
        MarketBoardDao dao = new MarketBoardDao(jdbc);

        dao.browse(null, null, 21, 0);
        dao.findVisible(UUID.randomUUID());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(sql.capture(), anyMap(), any(RowMapper.class));
        assertThat(sql.getAllValues()).hasSize(2);
        assertThat(sql.getAllValues()).allSatisfy(statement -> assertThat(statement)
                .as("the board's visibility predicate")
                .contains(ACTIVE_ONLY)
                .contains(NOT_KILLED)
                .contains(NOT_BLOCKED));
    }

    /** The filtered variants are the same predicate plus a filter, never a different predicate. */
    @Test
    @SuppressWarnings("unchecked")
    void theQueryAndCityFiltersDoNotReplaceTheVisibilityPredicate() {
        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());
        MarketBoardDao dao = new MarketBoardDao(jdbc);

        dao.browse("telefon", "BAKU", 21, 0);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), anyMap(), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains(ACTIVE_ONLY)
                .contains(NOT_KILLED)
                .contains(NOT_BLOCKED)
                // V15: the filter is equality on a CODE, so there is no fold column left to
                // read and a city-filtered board is the same predicate plus one equality.
                .contains("l.city = :city");
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePublicSqlNamesOnlyListingsAndTheBlockList() {
        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());
        MarketBoardDao dao = new MarketBoardDao(jdbc);

        dao.browse("telefon", "BAKU", 21, 0);
        dao.findVisible(UUID.randomUUID());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(sql.capture(), anyMap(), any(RowMapper.class));
        assertThat(sql.getAllValues()).allSatisfy(statement -> assertThat(statement.toLowerCase())
                .as("tables an anonymous query may name")
                .contains("from listings l")
                // `users` would be the password file; the other three could carry a location path.
                .doesNotContain(" users")
                .doesNotContain("locations")
                .doesNotContain("spaces")
                .doesNotContain("item_files"));
    }
}
