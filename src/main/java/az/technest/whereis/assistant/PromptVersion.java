package az.technest.whereis.assistant;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A stable, machine-independent identifier for a system prompt's text: {@code "sha256:"} plus the
 * first 12 hex characters of SHA-256 over the UTF-8 bytes. Providers compute it once per class load
 * over the IMMUTABLE instruction constant — never over a per-request assembly (the Claude placement
 * prompt appends the caller's space names, which would give every request a unique "version" and
 * destroy the grouping key this column exists for).
 */
public final class PromptVersion {

    private static final int HEX_CHARS = 12;

    private PromptVersion() {
    }

    /**
     * The version of a prompt whose instructions are only the system text.
     */
    public static String of(String promptText) {
        return digest(promptText);
    }

    /**
     * The version of a prompt that ALSO instructs through a structured-output schema.
     *
     * <p>A provider using {@code output_config.format} sends the schema derived from these records
     * alongside the system text, and every {@link JsonPropertyDescription} in it is instruction the
     * model reads. Hashing the system text alone would report one version for two prompts that tell
     * the model different things — which is precisely what this column exists to distinguish. Enum
     * constants count too: they are the allowed values the schema constrains the model to.
     *
     * <p>Rendering is declaration-ordered and therefore stable across JVMs; each type is rendered
     * once, so a type referenced twice cannot change the digest and a cyclic schema cannot hang.
     */
    public static String of(String promptText, Class<?>... schemas) {
        StringBuilder canonical = new StringBuilder(promptText);
        Set<Class<?>> rendered = new LinkedHashSet<>();
        for (Class<?> schema : schemas) {
            render(schema, canonical, rendered);
        }
        return digest(canonical.toString());
    }

    private static void render(Class<?> type, StringBuilder out, Set<Class<?>> rendered) {
        if (type == null || !rendered.add(type)) {
            return;
        }
        if (type.isEnum()) {
            out.append('\n').append(type.getSimpleName()).append('[');
            for (Object constant : type.getEnumConstants()) {
                out.append(((Enum<?>) constant).name()).append(',');
            }
            out.append(']');
            return;
        }
        if (!type.isRecord()) {
            return;
        }
        out.append('\n').append(type.getSimpleName()).append('{');
        for (RecordComponent component : type.getRecordComponents()) {
            JsonPropertyDescription description = component.getAnnotation(JsonPropertyDescription.class);
            out.append(component.getName()).append('=')
                    .append(description == null ? "" : description.value()).append(';');
        }
        out.append('}');
        // Nested types after the parent's own text, so the parent stays contiguous and readable.
        for (RecordComponent component : type.getRecordComponents()) {
            render(component.getType(), out, rendered);
            if (component.getGenericType() instanceof ParameterizedType parameterized) {
                for (Type argument : parameterized.getActualTypeArguments()) {
                    if (argument instanceof Class<?> argumentClass) {
                        render(argumentClass, out, rendered);
                    }
                }
            }
        }
    }

    private static String digest(String promptText) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(promptText.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest).substring(0, HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java platform; reaching here is a broken JRE.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
