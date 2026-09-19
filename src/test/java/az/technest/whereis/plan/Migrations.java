package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Reads the Flyway migrations the way the database sees them: <strong>in version order, with the
 * EFFECTIVE value of a constraint after every ADD and DROP has been applied</strong>.
 *
 * <p>The enum-vs-CHECK drift guards used to parse ONE file each. That stops guarding the moment a
 * later migration widens the same constraint: the regex still finds the older, narrower CHECK and
 * the test still passes, so the obvious minimal repair (repoint it at the new file) silently drops
 * the property it was protecting — nothing would then verify that the old constraint was actually
 * dropped, and a migration that adds a constant while leaving an older constraint in place would
 * pass the test and fail at runtime on the first insert.
 */
final class Migrations {

    private static final Pattern VERSION = Pattern.compile("V(\\d+)__");

    private Migrations() {
    }

    record Migration(int version, String name, String sql) {
    }

    /** Every migration on the classpath, in version order, with SQL comments stripped. */
    static List<Migration> all() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/V*.sql");
            assertThat(resources).as("migrations on the classpath").isNotEmpty();
            List<Migration> migrations = new ArrayList<>();
            for (Resource resource : resources) {
                String name = resource.getFilename();
                Matcher matcher = VERSION.matcher(name);
                assertThat(matcher.find()).as("a version in " + name).isTrue();
                migrations.add(new Migration(Integer.parseInt(matcher.group(1)), name,
                        stripComments(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8))));
            }
            migrations.sort(Comparator.comparingInt(Migration::version));
            return migrations;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The values a named CHECK constraint allows AFTER the whole migration set has run, as a
     * database would see them. Fails if the constraint was dropped and never re-added, or if two
     * migrations left two constraints of the same name (which PostgreSQL would refuse anyway).
     *
     * @param constraintName e.g. {@code ck_users_plan}
     * @param column         the column inside {@code CHECK (<column> IN (...))}
     */
    static List<String> effectiveCheckValues(String constraintName, String column) {
        Pattern add = Pattern.compile("CONSTRAINT\\s+" + constraintName + "\\s+CHECK\\s*\\(\\s*"
                + column + "\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Pattern drop = Pattern.compile("DROP\\s+CONSTRAINT\\s+" + constraintName + "\\b",
                Pattern.CASE_INSENSITIVE);
        List<String> allowed = null;
        for (Migration migration : all()) {
            if (drop.matcher(migration.sql()).find()) {
                allowed = null;
            }
            Matcher matcher = add.matcher(migration.sql());
            while (matcher.find()) {
                assertThat(allowed)
                        .as(constraintName + " is added twice without a DROP (" + migration.name() + ")")
                        .isNull();
                allowed = Arrays.stream(matcher.group(1).split(","))
                        .map(value -> value.trim().replace("'", ""))
                        .filter(value -> !value.isEmpty())
                        .toList();
            }
        }
        assertThat(allowed).as("a surviving " + constraintName + " across every migration").isNotNull();
        return allowed;
    }

    /** Every migration, joined, for "this statement appears nowhere" assertions. */
    static String allStatements() {
        return all().stream().map(Migration::sql).collect(Collectors.joining("\n"));
    }

    private static String stripComments(String sql) {
        return sql.lines()
                .map(line -> line.replaceFirst("--.*$", ""))
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));
    }
}
