package az.technest.whereis.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.technest.whereis.assistant.claude.ClaudeAssistant;
import az.technest.whereis.assistant.claude.ClaudeProperties;
import az.technest.whereis.assistant.mock.MockAiAssistant;
import az.technest.whereis.assistant.openai.OpenAiAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Provider selection and the fail-fast checks around it. Also pins the property binding: the
 * {@code ai.claude.*} sub-tree and the flat {@code ai.*} keys are bound by two different records
 * under overlapping prefixes, which is exactly the arrangement worth having a test for.
 */
class AiConfigTest {

    private static final ClaudeProperties NO_CLAUDE_CONFIG =
            new ClaudeProperties(null, null, null, null, null, 0, 0.0, null);

    private static AiProperties mockProvider() {
        return new AiProperties("mock", null, null, null, 0.0, null, 0);
    }

    @Test
    void mockIsSelectedWithNoKeysAtAll() {
        assertThat(new AiConfig().aiAssistant(mockProvider(), NO_CLAUDE_CONFIG, new ObjectMapper()))
                .isInstanceOf(MockAiAssistant.class);
    }

    @Test
    void openaiIsStillSelectable() {
        AiProperties properties = new AiProperties(
                "openai", "https://ai.example/v1", "k", "m", 0.0, Duration.ofSeconds(5), 800);
        assertThat(new AiConfig().aiAssistant(properties, NO_CLAUDE_CONFIG, new ObjectMapper()))
                .isInstanceOf(OpenAiAssistant.class);
    }

    @Test
    void claudeIsSelectedAndOpensNoSocketAtStartup() {
        AiProperties properties = new AiProperties("claude", null, null, null, 0.0, null, 0);
        // Port 1 has nothing listening on it: an eagerly-connecting client would fail here, so a
        // successful build is what proves the socket is not opened until the first interpret call.
        ClaudeProperties claude =
                new ClaudeProperties("test-key", "http://127.0.0.1:1", null, null, null, 0, 0.0, null);

        assertThat(new AiConfig().aiAssistant(properties, claude, new ObjectMapper()))
                .isInstanceOf(ClaudeAssistant.class);
    }

    /**
     * Regression guard for a real 400 seen against the live API: an identity-linked API key is
     * rejected unless the request names the workspace it acts in. The offline suite could not have
     * caught it — the loopback server answers whatever it is sent — so the header is pinned here.
     */
    @Test
    void aConfiguredWorkspaceIdIsSentAsAHeaderAndOmittedWhenUnset() throws IOException {
        assertThat(workspaceHeaderFor("wrkspc_test_123")).isEqualTo("wrkspc_test_123");
        assertThat(workspaceHeaderFor(null))
                .as("a workspace-scoped key must not have the header forced on it")
                .isNull();
    }

    /** Returns the anthropic-workspace-id the built client actually put on the wire, or null. */
    private static String workspaceHeaderFor(String workspaceId) throws IOException {
        HttpServer server =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> seen = new ArrayList<>();
        server.createContext("/v1/messages", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("anthropic-workspace-id");
            seen.add(header == null ? "" : header);
            exchange.sendResponseHeaders(500, 2);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            String base = "http://" + InetAddress.getLoopbackAddress().getHostAddress()
                    + ":" + server.getAddress().getPort();
            ClaudeProperties claude = new ClaudeProperties(
                    "k", base, workspaceId, null, Duration.ofSeconds(5), 0, 0.0, 0);
            AiAssistant assistant = new AiConfig().aiAssistant(
                    new AiProperties("claude", null, null, null, 0.0, null, 0), claude,
                    new ObjectMapper());
            assertThatThrownBy(() -> assistant.interpretSearch("where is my passport?"))
                    .isInstanceOf(AiAssistantException.class);
            return seen.getFirst().isEmpty() ? null : seen.getFirst();
        } finally {
            server.stop(0);
        }
    }

    /**
     * The one branch in {@code AiConfig#anthropicClient} that only fires for a non-null base URL.
     * Asserting the built client actually calls that host is the only way to catch a dropped
     * {@code builder.baseUrl(...)} — the client exposes no getter for it.
     */
    @Test
    void aConfiguredBaseUrlIsTheHostTheBuiltClientCalls() throws IOException {
        HttpServer server =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> hits = new ArrayList<>();
        server.createContext("/v1/messages", exchange -> {
            hits.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(500, 2);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            String base = "http://" + InetAddress.getLoopbackAddress().getHostAddress()
                    + ":" + server.getAddress().getPort();
            // maxRetries 0 so one call is exactly one hit; a 500 is enough to prove where it went.
            ClaudeProperties claude =
                    new ClaudeProperties("k", base, null, null, Duration.ofSeconds(5), 0, 0.0, 0);
            AiAssistant assistant = new AiConfig().aiAssistant(
                    new AiProperties("claude", null, null, null, 0.0, null, 0), claude,
                    new ObjectMapper());

            assertThatThrownBy(() -> assistant.interpretSearch("where is my passport?"))
                    .isInstanceOf(AiAssistantException.class);

            assertThat(hits).containsExactly("/v1/messages");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void claudeWithoutAKeyFailsFastAndSaysWhichKey() {
        AiProperties properties = new AiProperties("claude", null, null, null, 0.0, null, 0);

        assertThatThrownBy(() -> new AiConfig().aiAssistant(properties, NO_CLAUDE_CONFIG, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_CLAUDE_API_KEY")
                .hasMessageContaining("ai.provider=claude");
    }

    @Test
    void anUnknownProviderNamesTheSupportedOnes() {
        AiProperties properties = new AiProperties("gemini", null, null, null, 0.0, null, 0);

        assertThatThrownBy(() -> new AiConfig().aiAssistant(properties, NO_CLAUDE_CONFIG, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mock, openai, claude");
    }

    /**
     * Binds by {@code @ConfigurationPropertiesScan} rather than by naming the two records, which
     * is what proves a scan rooted above {@code assistant} reaches the new {@code assistant.claude}
     * sub-package — the app's own scan on {@code WhereisApplication} is rooted higher still. The
     * root is narrowed to the assistant tree only so the context does not also demand a JWT secret
     * and MinIO credentials it has nothing to do with.
     */
    @Test
    void bothPropertyRecordsBindUnderTheirOverlappingPrefixes() {
        new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(ScannedBindingConfig.class)
                .withPropertyValues(
                        "ai.provider=claude",
                        "ai.model=gpt-4o-mini",
                        "ai.claude.api-key=secret",
                        "ai.claude.model=claude-haiku-4-5",
                        "ai.claude.max-retries=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AiProperties ai = context.getBean(AiProperties.class);
                    ClaudeProperties claude = context.getBean(ClaudeProperties.class);
                    assertThat(ai.provider()).isEqualTo("claude");
                    assertThat(ai.model()).isEqualTo("gpt-4o-mini");
                    assertThat(claude.apiKey()).isEqualTo("secret");
                    assertThat(claude.model()).isEqualTo("claude-haiku-4-5");
                    // A deliberate 0 must survive; only an unset value falls back to 1.
                    assertThat(claude.maxRetries()).isZero();
                });
    }

    /**
     * The binding test above never instantiates {@code AiConfig}, so the {@code claude} branch was
     * only ever reached through a direct {@code new AiConfig()}. These two boot a context that
     * registers it, which is what a real {@code ai.provider=claude} deployment does.
     */
    @Test
    void aClaudeContextProducesAClaudeAssistantBean() {
        claudeContext("ai.provider=claude", "ai.claude.api-key=secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AiAssistant.class)).isInstanceOf(ClaudeAssistant.class);
                });
    }

    @Test
    void aClaudeContextWithoutAKeyRefusesToStart() {
        claudeContext("ai.provider=claude")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageContaining("AI_CLAUDE_API_KEY"));
    }

    private static ApplicationContextRunner claudeContext(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(ScannedBindingConfig.class, AiConfig.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(properties);
    }

    @org.springframework.boot.context.properties.ConfigurationPropertiesScan("az.technest.whereis.assistant")
    static class ScannedBindingConfig {
    }
}
