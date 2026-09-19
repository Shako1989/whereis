package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Google Play requires the account-deletion and privacy pages to be reachable by anyone, with no
 * account. The negative half proves the new {@code /legal/**} matcher opened nothing under {@code /api}.
 */
class LegalPagesIT extends AbstractIntegrationTest {

    private ResponseEntity<String> anonymousGet(String path) {
        return rest.getForEntity(path, String.class);
    }

    private static void assertHtmlPage(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(response.getBody()).startsWith("<!DOCTYPE html>");
    }

    @Test
    void deleteAccountPageIsAnonymousBilingualAndExplainsBothRoutes() {
        ResponseEntity<String> page = anonymousGet("/legal/delete-account");

        assertHtmlPage(page);
        String body = page.getBody();
        // Azerbaijani first, English second, on the same page.
        assertThat(body).contains("Hesabın silinməsi").contains("Delete account");
        // The in-app route is spelled out in both languages…
        assertThat(body).contains("Parametrlər").contains("Hesabı sil").contains("Settings");
        // …and the e-mail fallback is a mailto link to the (still placeholder) support address.
        // The dev-profile value from application.yml. Asserting the RENDERED address rather than
        // the marker is the whole point of this change: the marker reaching a reader was the bug.
        assertThat(body).contains("mailto:support@example.invalid");
        // Photos are removed asynchronously — the page must not promise "instantly".
        assertThat(body).contains("asynchronously").contains("asinxron");
    }

    @Test
    void privacyPageIsAnonymousAndBilingual() {
        ResponseEntity<String> page = anonymousGet("/legal/privacy");

        assertHtmlPage(page);
        assertThat(page.getBody()).contains("Məxfilik bildirişi").contains("Privacy notice");
        assertThat(page.getBody()).contains("/legal/delete-account");
    }

    @Test
    void theHtmlFormOfTheUrlIsServedToo() {
        // The clean URL forwards to the .html resource; the target must be permitted as well.
        assertHtmlPage(anonymousGet("/legal/delete-account.html"));
        assertHtmlPage(anonymousGet("/legal/privacy.html"));
    }

    @Test
    void noServedPageCarriesATemplateMarker() {
        // The one assertion that would have caught the original defect. Both pages, both URL
        // forms, because the .html form used to be served straight off the classpath by the static
        // resource handler — which is why the source files no longer live under static/.
        for (String path : new String[] {
                "/legal/privacy", "/legal/privacy.html",
                "/legal/delete-account", "/legal/delete-account.html"}) {
            ResponseEntity<String> page = anonymousGet(path);
            assertThat(page.getStatusCode()).as("%s", path).isEqualTo(HttpStatus.OK);
            assertThat(page.getBody()).as("%s must not be a template", path).doesNotContain("{{");
        }
    }

    @Test
    void aHeadRequestIsAnsweredToo() {
        // Link checkers and store tooling probe with HEAD; a GET-only matcher answered those 401,
        // which reads as a broken privacy URL to whoever is checking.
        // Accept is set explicitly: TestRestTemplate derives one from the Void response type,
        // which matches nothing a text/html-only endpoint can produce. A real checker sends */*.
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.ALL));
        assertThat(rest.exchange("/legal/privacy", HttpMethod.HEAD, new HttpEntity<>(headers), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unknownLegalPathsAre404NotAHole() {
        ResponseEntity<String> missing = anonymousGet("/legal/does-not-exist");

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theLegalMatcherOpensNothingUnderTheApi() {
        assertThat(anonymousGet("/api/v1/spaces").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymousGet("/api/v1/items").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> anonymousDelete = rest.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>("{\"password\":\"" + PASSWORD + "\"}", json), String.class);
        assertThat(anonymousDelete.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
