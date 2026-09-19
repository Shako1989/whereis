package az.technest.whereis.plan.rtdn;

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
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The two enums V11 introduces, pinned against the CHECK constraints that store them — written the
 * way {@code SubscriptionStateTest} is written, and for the same reason: a Java constant with no
 * matching CHECK is an insert that fails at runtime, and a CHECK value with no constant is a row
 * nothing can read back.
 *
 * <p>It also pins the THIRD constraint V11 adds, which is a different statement from either enum
 * pin: {@code ck_play_notifications_outcome_matches_processed} says which outcomes set
 * {@code processed_at}, and {@link PlayNotificationOutcome#setsProcessedAt()} says the same thing in
 * Java. If those two disagree, the ledger either refuses a legitimate write or advances a watermark
 * for a message that applied nothing.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlayNotificationEnumsTest {

    @Test
    void everyNotificationKindHasACheckValueAndEveryCheckValueHasAConstant() {
        assertThat(checkValues("ck_play_notifications_kind", "notification_kind"))
                .containsExactlyInAnyOrderElementsOf(names(PlayNotificationKind.values()));
    }

    @Test
    void everyOutcomeHasACheckValueAndEveryCheckValueHasAConstant() {
        assertThat(checkValues("ck_play_notifications_outcome", "outcome"))
                .containsExactlyInAnyOrderElementsOf(names(PlayNotificationOutcome.values()));
    }

    @Test
    void theOutcomesThatSetProcessedAtAreExactlyTheOnesTheCheckPermitsThere() {
        // The CHECK lists the four SUCCEEDED outcomes in its processed_at IS NOT NULL branch and the
        // two terminal-but-unapplied ones in the other. setsProcessedAt() must agree with both
        // halves or a legitimate finish() becomes a constraint violation — which, on this endpoint,
        // Pub/Sub would retry for seven days.
        List<String> succeeded = branchValues("processed_at IS NOT NULL\\s+AND outcome IN \\(([^)]*)\\)");
        List<String> unapplied = branchValues(
                "processed_at IS NULL\\s+AND \\(outcome IS NULL OR outcome IN \\(([^)]*)\\)");

        assertThat(succeeded).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(PlayNotificationOutcome.values())
                        .filter(PlayNotificationOutcome::setsProcessedAt).map(Enum::name).toList());
        assertThat(unapplied).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(PlayNotificationOutcome.values())
                        .filter(outcome -> !outcome.setsProcessedAt()).map(Enum::name).toList());
    }

    private static List<String> branchValues(String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(migrationText());
        assertThat(matcher.find()).as("a branch matching %s", regex).isTrue();
        return Arrays.stream(matcher.group(1).split(","))
                .map(value -> value.trim().replace("'", ""))
                .filter(value -> !value.isEmpty())
                .toList();
    }

    @Test
    void aMalformedRowIsTheOnlyOneAllowedToOmitTheDecodedFields() {
        // The correction a review found: MALFORMED is defined as exactly the cases where
        // event_time_millis and package_name do not exist, and V10 declared both NOT NULL — so the
        // one row the ledger exists to preserve could not be written at all, and the poison message
        // looped for the full 7-day retention while the ledger recorded nothing.
        String sql = migrationText();
        assertThat(sql)
                .contains("ALTER COLUMN event_time_millis DROP NOT NULL")
                .contains("ALTER COLUMN package_name DROP NOT NULL")
                .contains("ck_play_notifications_decoded_unless_malformed");
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    /** The values a named CHECK allows, read from the migrations the way the database sees them. */
    private static List<String> checkValues(String constraintName, String column) {
        Pattern add = Pattern.compile("CONSTRAINT\\s+" + constraintName + "\\s+CHECK\\s*\\([^(]*"
                + column + "\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = add.matcher(migrationText());
        assertThat(matcher.find()).as("a %s constraint in the migrations", constraintName).isTrue();
        return Arrays.stream(matcher.group(1).split(","))
                .map(value -> value.trim().replace("'", ""))
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /** Every migration, in version order, with SQL comments stripped so prose cannot match. */
    private static String migrationText() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/V*.sql");
            List<String> sql = new ArrayList<>();
            List<Resource> ordered = new ArrayList<>(Arrays.asList(resources));
            ordered.sort(Comparator.comparingInt(PlayNotificationEnumsTest::versionOf));
            for (Resource resource : ordered) {
                sql.add(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                        .lines()
                        .map(line -> line.replaceFirst("--.*$", ""))
                        .filter(line -> !line.isBlank())
                        .collect(Collectors.joining("\n")));
            }
            return String.join("\n", sql);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int versionOf(Resource resource) {
        Matcher matcher = Pattern.compile("V(\\d+)__").matcher(resource.getFilename());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
