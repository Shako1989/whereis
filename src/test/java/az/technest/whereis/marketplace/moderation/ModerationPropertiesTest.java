package az.technest.whereis.marketplace.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * <strong>The SHIPPED value of this property is blank</strong> — {@code application.yml} defaults
 * {@code WHEREIS_MARKETPLACE_MODERATOR_EMAILS} to nothing at all, so every environment that has not
 * been configured yet runs exactly the case asserted first here. {@code SellerBlockServiceTest}
 * proves an empty allowlist refuses everybody; this proves that blank configuration IS an empty
 * allowlist, which is the step in between and the one no service test can reach.
 *
 * <p>It binds through Spring's real {@link Binder} rather than calling the constructor, because the
 * question is what Spring does with {@code ""} for a {@code List<String>} component: a one-element
 * list holding an empty string would be an allowlist of size one, and the difference between that
 * and an empty set is the difference between "nobody may moderate" and "an account with a blank
 * e-mail may", which is a state {@code Names.normalize} could plausibly produce.
 */
class ModerationPropertiesTest {

    private static ModerationProperties bind(String configured) {
        return new Binder(new MapConfigurationPropertySource(
                Map.of("whereis.marketplace.moderation.moderator-emails", configured)))
                .bind("whereis.marketplace.moderation", ModerationProperties.class)
                .orElseGet(() -> new ModerationProperties(null));
    }

    @Test
    void theShippedBlankDefaultBindsToAnAllowlistOfNobody() {
        assertThat(bind("").normalizedModeratorEmails()).isEmpty();
        assertThat(bind("   ").normalizedModeratorEmails()).isEmpty();
        // Not even a list of separators can produce an entry.
        assertThat(bind(",,").normalizedModeratorEmails()).isEmpty();
    }

    @Test
    void aCommaSeparatedListBindsAndIsNormalizedTheWayRegistrationStoredTheEmail() {
        // Registration writes users.email through Names.normalize, so the allowlist has to be
        // compared in that form or a capitalised environment variable matches nobody and the only
        // symptom is a 403 for the person who set it.
        ModerationProperties properties = bind(" OPS@TechNest.AZ , second@example.com ");

        assertThat(properties.normalizedModeratorEmails())
                .containsExactlyInAnyOrder("ops@technest.az", "second@example.com");
        // The raw component keeps what the operator wrote, which is what a startup dump should show.
        assertThat(properties.moderatorEmails()).hasSize(2);
    }

    /** An absent key is the same as a blank one: nobody, and certainly not a null list. */
    @Test
    void anAbsentKeyBindsToNobodyRatherThanNull() {
        ModerationProperties properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bind("whereis.marketplace.moderation", ModerationProperties.class)
                .orElseGet(() -> new ModerationProperties(null));

        assertThat(properties.moderatorEmails()).isNotNull().isEmpty();
        assertThat(properties.normalizedModeratorEmails()).isEmpty();
    }
}
