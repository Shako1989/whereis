package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * V9-vs-enum drift guard, the same shape as {@code AssistantOutcomeTest}: {@code users.plan} is a
 * varchar pinned by a CHECK and Hibernate writes the Java constant names, so a new constant without
 * a migration must fail the build instead of the first request.
 *
 * <p>The second test guards something that would be easy to "fix" by accident: V9 deliberately
 * contains no {@code UPDATE}, so no account — not even the one that existed before the free tier —
 * is grandfathered into UNLIMITED. Granting is an operator action, not a migration.
 */
class PlanTest {

    private static final String V9 = "db/migration/V9__user_plan.sql";

    private static String sql() throws IOException {
        try (InputStream in = PlanTest.class.getClassLoader().getResourceAsStream(V9)) {
            assertThat(in).as(V9 + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The migration with its comments stripped — the comments discuss the UPDATE that is absent. */
    private static String statements() throws IOException {
        return sql().lines()
                .map(line -> line.replaceFirst("--.*$", ""))
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining(" "));
    }

    @Test
    void constantsMatchTheV9CheckByteForByte() throws IOException {
        Matcher matcher = Pattern.compile("CHECK \\(plan IN \\(([^)]*)\\)\\)").matcher(sql());
        assertThat(matcher.find()).as("CHECK on plan").isTrue();
        List<String> allowed = Arrays.stream(matcher.group(1).split(","))
                .map(value -> value.trim().replace("'", ""))
                .toList();
        assertThat(allowed).containsExactly("FREE", "UNLIMITED");
        assertThat(Arrays.stream(Plan.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(allowed);
    }

    @Test
    void theMigrationDefaultsEveryRowToFreeAndGrantsNothing() throws IOException {
        String statements = statements();
        assertThat(statements).contains("DEFAULT 'FREE'");
        // No UPDATE anywhere: every pre-existing row, including the production account, stays FREE.
        assertThat(statements.toUpperCase()).doesNotContain("UPDATE");
    }
}
