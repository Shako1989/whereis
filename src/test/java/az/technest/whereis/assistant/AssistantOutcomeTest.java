package az.technest.whereis.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Enum-vs-CHECK drift guard: {@code assistant_messages.mode} and {@code .outcome} are varchar
 * columns pinned by CHECK constraints in V8, and Hibernate writes the enum constant names. The day
 * someone adds a constant without a V9, this fails the build instead of the first request.
 */
class AssistantOutcomeTest {

    private static final String V8 = "db/migration/V8__assistant_messages.sql";

    private static List<String> checkValues(String column) throws IOException {
        try (InputStream in = AssistantOutcomeTest.class.getClassLoader().getResourceAsStream(V8)) {
            assertThat(in).as(V8 + " on the classpath").isNotNull();
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("CHECK \\(" + column + " IN \\(([^)]*)\\)\\)").matcher(sql);
            assertThat(matcher.find()).as("CHECK on " + column).isTrue();
            return Arrays.stream(matcher.group(1).split(","))
                    .map(value -> value.trim().replace("'", ""))
                    .toList();
        }
    }

    @Test
    void modeConstantsMatchTheV8CheckByteForByte() throws IOException {
        assertThat(checkValues("mode")).containsExactly("REMEMBER", "SEARCH");
        assertThat(Arrays.stream(AssistantMode.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(checkValues("mode"));
    }

    @Test
    void outcomeConstantsMatchTheV8CheckByteForByte() throws IOException {
        assertThat(checkValues("outcome"))
                .containsExactly("CREATED", "NEEDS_CONFIRMATION", "NOT_UNDERSTOOD", "ANSWERED", "FAILED");
        assertThat(Arrays.stream(AssistantOutcome.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(checkValues("outcome"));
    }
}
