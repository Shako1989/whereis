package az.technest.whereis.common.legal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Renders the two public legal pages once, at startup, from {@code classpath:legal/} and
 * {@link LegalProperties}.
 *
 * <p>The files deliberately do NOT live under {@code static/}. Spring's static resource handler
 * would serve them straight from the jar, placeholders and all, at {@code /legal/privacy.html} —
 * which is precisely the hole this class exists to close, and it would sit behind the same
 * permitAll matcher as the rendered page.
 *
 * <p><strong>A leftover placeholder fails startup.</strong> These two pages are the privacy notice
 * and the account-deletion instructions Google Play links to from the store listing; serving
 * {@code {{SUPPORT_EMAIL}}} to a reviewer, or to a user looking for how to delete their account, is
 * worse than not booting. Rendering eagerly in the constructor is what turns a misconfiguration
 * into a startup error instead of a page nobody looks at until it matters.
 */
@Component
public class LegalPages {

    /** Matches the {@code {{NAME}}} markers the source files carry. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");

    private final Map<String, String> rendered = new LinkedHashMap<>();

    public LegalPages(LegalProperties properties) {
        // A HashMap and not Map.of: Map.of rejects nulls with a bare NullPointerException, which
        // would replace this class's one useful error message ("set whereis.legal.*") with a stack
        // trace, for the most likely misconfiguration there is — an unset property.
        Map<String, String> values = new HashMap<>();
        values.put("SUPPORT_EMAIL", properties.supportEmail());
        values.put("LEGAL_ENTITY", properties.legalEntity());
        values.put("LEGAL_ADDRESS", properties.legalAddress());
        values.put("EFFECTIVE_DATE", properties.effectiveDate());
        values.put("BACKUP_RETENTION_DAYS", properties.backupRetentionDays());
        values.put("CANCELLATION_RETRY_DAYS", properties.cancellationRetryDays());
        for (String page : new String[] {"privacy", "delete-account"}) {
            rendered.put(page, render(page, read(page), values));
        }
    }

    /** The rendered page, or {@code null} when nothing is published under that name. */
    public String page(String name) {
        return rendered.get(name);
    }

    private static String read(String page) {
        ClassPathResource resource = new ClassPathResource("legal/" + page + ".html");
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Legal page not on the classpath: " + resource.getPath(), e);
        }
    }

    private static String render(String page, String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(
                        "Legal page " + page + ".html still has {{" + name + "}}: set whereis.legal.*"
                                + " (WHEREIS_LEGAL_* in deploy/.env). Refusing to serve a template"
                                + " to the public.");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
