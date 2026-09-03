package az.technest.whereis.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.assistant.claude.ClaudeProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ClaudePropertiesTest {

    private static ClaudeProperties of(String model, Duration timeout, int maxOutputTokens,
            Double temperature, Integer maxRetries) {
        return new ClaudeProperties("k", null, null, model, timeout, maxOutputTokens, temperature, maxRetries);
    }

    @Test
    void unsetValuesFallBackToTheHaikuTunedDefaults() {
        ClaudeProperties properties = of(null, null, 0, 0.0, null);

        assertThat(properties.model()).isEqualTo("claude-haiku-4-5");
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.maxOutputTokens()).isEqualTo(4096);
        assertThat(properties.maxRetries()).isEqualTo(1);
        assertThat(properties.temperature()).isZero();
    }

    @Test
    void explicitValuesArePreserved() {
        ClaudeProperties properties =
                of("  claude-opus-4-6  ", Duration.ofSeconds(45), 8192, 0.4, 3);

        assertThat(properties.model()).isEqualTo("claude-opus-4-6");
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(properties.maxOutputTokens()).isEqualTo(8192);
        assertThat(properties.temperature()).isEqualTo(0.4);
        assertThat(properties.maxRetries()).isEqualTo(3);
    }

    @Test
    void aBlankKeyOrBaseUrlBecomesNullSoTheProviderCheckAndTheSdkDefaultBothWork() {
        ClaudeProperties properties =
                new ClaudeProperties("   ", "  ", "  ", null, null, 0, 0.0, null);

        assertThat(properties.apiKey()).isNull();
        assertThat(properties.baseUrl()).isNull();
        assertThat(properties.workspaceId()).isNull();
    }

    @Test
    void anOutOfRangeOrNaNTemperatureNeverReachesTheProvider() {
        assertThat(of(null, null, 0, 1.5, null).temperature()).isZero();
        assertThat(of(null, null, 0, -0.1, null).temperature()).isZero();
        assertThat(of(null, null, 0, Double.NaN, null).temperature()).isZero();
    }

    @Test
    void aNullTemperatureIsPreservedSoTheParameterCanBeOmittedEntirely() {
        // Distinct from 0.0: null means "do not send temperature at all", which is what lets the
        // model be raised to one that rejects sampling parameters.
        assertThat(of(null, null, 0, null, null).temperature()).isNull();
        assertThat(of("claude-sonnet-5", null, 0, null, null).temperature()).isNull();
    }

    @Test
    void aNonPositiveTimeoutFallsBack() {
        assertThat(of(null, Duration.ZERO, 0, 0.0, null).timeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(of(null, Duration.ofSeconds(-5), 0, 0.0, null).timeout()).isEqualTo(Duration.ofSeconds(15));
    }
}
