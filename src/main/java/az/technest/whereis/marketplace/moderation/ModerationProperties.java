package az.technest.whereis.marketplace.moderation;

import az.technest.whereis.common.util.Names;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who may act on the marketplace. <strong>An allowlist of e-mail addresses that FAILS CLOSED:
 * unset means NOBODY, never everybody.</strong>
 *
 * <p>This application has no role column and no admin account, and adding one for two endpoints
 * would be a migration made for the wrong reason — a {@code users.role} column is a permission
 * model, and a permission model wants a story for granting, revoking, auditing and testing it that
 * two operator actions do not justify. The operator is a user of their own application, so the JWT
 * they already hold is the credential and their e-mail is the identity they actually configure.
 * That is also what makes the action ATTRIBUTABLE: the same e-mail is written into
 * {@code blocked_sellers.blocked_by}.
 *
 * <p>Entries are compared as {@link Names#normalize(String)} of the configured value against the
 * stored {@code users.email}, which registration already normalized the same way — one
 * normalization, so a capitalised entry in a deployment's environment cannot silently match nobody.
 *
 * <p>The set is recomputed per call rather than cached: it is consulted once per moderation
 * request, of which this deployment expects a handful a year, and a cached copy is one more thing
 * that can be stale.
 *
 * @param moderatorEmails {@code whereis.marketplace.moderation.moderator-emails}, i.e.
 *                        {@code WHEREIS_MARKETPLACE_MODERATION_MODERATOR_EMAILS} — comma-separated
 */
@ConfigurationProperties("whereis.marketplace.moderation")
public record ModerationProperties(List<String> moderatorEmails) {

    public ModerationProperties {
        moderatorEmails = moderatorEmails == null ? List.of() : List.copyOf(moderatorEmails);
    }

    /** The allowlist in {@code users.email}'s own normalized form. An empty set is nobody. */
    public Set<String> normalizedModeratorEmails() {
        return moderatorEmails.stream()
                .map(Names::normalize)
                .filter(email -> email != null && !email.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
