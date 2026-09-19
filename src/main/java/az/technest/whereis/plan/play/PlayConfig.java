package az.technest.whereis.plan.play;

import az.technest.whereis.plan.PlanCatalog;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.AndroidPublisherScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Selects the {@link PlaySubscriptionsApi} implementation, in the shape {@code AiConfig} already
 * proved: a switch over one property, credentials built inside the branch that needs them, and a
 * clear startup failure for anything else.
 */
@Configuration
public class PlayConfig {

    private static final Profiles PROD = Profiles.of("prod");

    @Bean
    public PlaySubscriptionsApi playSubscriptionsApi(PlayProperties properties, PlanCatalog catalog,
                                                     Environment environment) {
        return switch (properties.provider()) {
            case PlayProperties.FAKE -> {
                // THE FAKE IS STRUCTURALLY UNAVAILABLE IN PRODUCTION, not merely unselected. Its
                // tokens are guessable literals, so a prod process that had it would hand the top
                // tier to anyone who posted `fake-active-max` — a self-service entitlement
                // escalation gated on one environment variable's VALUE, which is exactly the kind
                // of thing that gets set while fixing a boot failure at 2am. This is not the
                // AiAssistant precedent: the AI mock cannot grant anything.
                if (environment.acceptsProfiles(PROD)) {
                    throw new IllegalStateException(
                            "whereis.play.provider=fake is refused under the prod profile: the fake Play API "
                                    + "grants any tier to anyone who guesses a token. Set PLAY_PROVIDER=google.");
                }
                yield new FakePlaySubscriptionsApi(catalog);
            }
            case PlayProperties.GOOGLE -> new GooglePlaySubscriptionsApi(androidPublisher(properties), properties);
            default -> throw new IllegalStateException("Unknown whereis.play.provider '" + properties.provider()
                    + "' (supported: " + PlayProperties.FAKE + ", " + PlayProperties.GOOGLE + ")");
        };
    }

    /**
     * Built here rather than as a bean, and only inside the {@code google} branch, so a
     * {@code fake} deployment still needs no Google key. This is also where the required-key check
     * lives: {@link PlayProperties} binds without validating, because it binds on every boot
     * regardless of which provider is selected.
     */
    private AndroidPublisher androidPublisher(PlayProperties properties) {
        if (properties.serviceAccountJson() == null) {
            throw new IllegalStateException("whereis.play.service-account-json (PLAY_SERVICE_ACCOUNT_JSON) "
                    + "must be configured when whereis.play.provider=google");
        }
        try {
            GoogleCredentials credentials = GoogleCredentials
                    .fromStream(new ByteArrayInputStream(properties.serviceAccountJson()
                            .getBytes(StandardCharsets.UTF_8)))
                    .createScoped(List.of(AndroidPublisherScopes.ANDROIDPUBLISHER));
            HttpCredentialsAdapter auth = new HttpCredentialsAdapter(credentials);
            int millis = (int) properties.timeout().toMillis();
            HttpRequestInitializer initializer = request -> {
                auth.initialize(request);
                request.setConnectTimeout(millis);
                request.setReadTimeout(millis);
            };
            return new AndroidPublisher.Builder(GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(), initializer)
                    .setApplicationName("whereis")
                    .build();
        } catch (IOException | GeneralSecurityException e) {
            // Never include the key material in the message; the cause carries enough to diagnose.
            throw new IllegalStateException("Could not build the Play Developer API client from "
                    + "whereis.play.service-account-json", e);
        }
    }
}
