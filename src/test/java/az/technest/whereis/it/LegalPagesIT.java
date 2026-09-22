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
    void theDeleteAccountPageStatesWhatHappensToAGooglePlaySubscriptionInBothLanguages() {
        // A compliance regression nothing else would catch: this paragraph is the only place a user
        // is told that deleting the account does not stop Google billing them, and that the
        // cancellation can FAIL — at which point their account, and their e-mail address, are gone
        // and only they can stop the charges.
        String body = anonymousGet("/legal/delete-account").getBody();

        assertThat(body).contains("Google Play abunəliyi").contains("Google Play subscriptions");
        // The retry window is RENDERED from whereis.legal.cancellation-retry-days — the same
        // property PlayCancellationJanitor gives up after — never a literal in the HTML.
        assertThat(body).contains("7 gün").contains("7 days");
        // The failure outcome, stated rather than promised away.
        assertThat(body).contains("may keep being charged")
                .contains("pul tutulmağa davam edə bilər");
        // And the self-service route, in both languages.
        assertThat(body).contains("Payments &amp; subscriptions").contains("Ödənişlər və abunəliklər");
    }

    @Test
    void thePrivacyPageAgreesWithItAboutTheRetainedPurchaseToken() {
        // The two pages are the URLs Play cross-checks for the Data-safety form. privacy.html used
        // to say deletion removes everything IMMEDIATELY while delete-account.html said a purchase
        // token survives for up to seven days — two public legal pages contradicting each other on
        // a retention period, which is a compliance defect on its own.
        String body = anonymousGet("/legal/privacy").getBody();

        assertThat(body).contains("purchase token").contains("satınalma nişanı");
        assertThat(body).contains("7 days").contains("7 gün");
        // Google is named as a processor: the server transmits the token and product id to it, and
        // a transmission the notice's own processor section omits is the kind of mismatch that gets
        // a listing rejected.
        assertThat(body).contains("Google LLC");
    }

    // ------------------------------------------------------------------------------------------
    // The five claims an audit of the code against the pages found overstated on 2026-09-20. Each
    // assertion below is BOTH halves — the corrected claim present in AZ and EN, and the
    // overstatement absent — because a fix applied to one language reads as deliberate rather than
    // as an oversight, and because the only thing that stops a future edit re-introducing a
    // sentence is a test that names it.
    // ------------------------------------------------------------------------------------------

    @Test
    void neitherPageClaimsTheBackupsAreEncryptedBecauseTheyAreNot() {
        // deploy/db_backup.sh is `pg_dump | gzip` and `tar czf`. There is no gpg and no openssl in
        // it, so "encrypted backups" / "şifrələnmiş ehtiyat nüsxələr" was a false attestation on the
        // two URLs Play cross-checks against the Data safety declaration. The claim went; the rest
        // of the sentence — the retention window, and what backups are for — stayed.
        for (String path : new String[] {"/legal/privacy", "/legal/delete-account"}) {
            String body = anonymousGet(path).getBody();
            assertThat(body).as("%s must not claim encryption in English", path)
                    .doesNotContainIgnoringCase("encrypted");
            assertThat(body).as("%s must not claim encryption in Azerbaijani", path)
                    .doesNotContainIgnoringCase("şifrələnmiş");
        }

        assertThat(anonymousGet("/legal/privacy").getBody())
                .contains("Backups may hold deleted data")
                .contains("Ehtiyat nüsxələr silinmiş məlumatları");
        assertThat(anonymousGet("/legal/delete-account").getBody())
                .contains("Database backups may still contain your data")
                .contains("Verilənlər bazasının ehtiyat nüsxələri");
    }

    @Test
    void bothPagesStateTheBillingNotificationLedgerAndItsRetentionWindow() {
        // play_notifications used to outlive the "hard delete" this page promises: the table has no
        // user_id, so nothing deleted its rows and it kept purchase tokens, order ids, product ids
        // and Google's raw payload indefinitely. The code now purges the rows that can be linked to
        // a deleted account and ages the rest out — and the window is RENDERED from
        // whereis.legal.billing-log-retention-days, the same property PlayNotificationJanitor uses
        // as its cutoff, never a literal in the HTML.
        for (String path : new String[] {"/legal/privacy", "/legal/delete-account"}) {
            String body = anonymousGet(path).getBody();
            assertThat(body).as("%s, English", path).contains("30 days");
            assertThat(body).as("%s, Azerbaijani", path).contains("30 gündən sonra");
        }

        // And the account-linked half, stated as a deletion rather than as a retention: the
        // "what is deleted" list names the ledger entries in both languages.
        assertThat(anonymousGet("/legal/delete-account").getBody())
                .contains("be linked to your account;")
                .contains("hesabınızla əlaqələndirilə bilən hər bir qeyd;");
    }

    @Test
    void theAnalyticsClaimIsNarrowedToWhatWeIntegratedRatherThanWhatShipsInside() {
        // "No analytics or tracking SDKs are used" was imprecise: Play Billing 9.1.0 carries
        // Google's own CCT transport. The honest claim is about what WE added, with the Billing
        // Library named as the exception — which is also what makes the Data safety answer
        // defensible instead of contradicted by the app's own dependency list.
        String body = anonymousGet("/legal/privacy").getBody();

        assertThat(body).doesNotContain("No analytics or tracking SDKs are used");
        assertThat(body).doesNotContain("izləmə SDK-ları istifadə edilmir");
        assertThat(body)
                .contains("We have integrated no analytics or tracking SDK into the app")
                .contains("heç bir analitika və ya izləmə SDK-sı əlavə edilməmişdir");
        // The exception named rather than implied: the Billing Library is what carries Google's own
        // components, and it is there because the app sells subscriptions.
        assertThat(body)
                .contains("Billing Library")
                .contains("Google Play Billing kitabxanası");
    }

    @Test
    void bothPagesDiscloseVoiceInputAndThatNoAudioReachesTheServer() {
        // AssistantScreen.kt fires RecognizerIntent.ACTION_RECOGNIZE_SPEECH and reads back only
        // EXTRA_RESULTS, so the device's own recognizer holds the microphone and whereis receives
        // text. Neither page mentioned voice, microphone or speech in either language, which is
        // what would have made "Audio files: not collected" on the Data safety form an unsupported
        // answer. The privacy notice discloses the collection; the deletion page answers the
        // question its own reader has ("what happens to my recordings?").
        String privacy = anonymousGet("/legal/privacy").getBody();
        assertThat(privacy)
                .contains("Voice input")
                .contains("your device's own speech service")
                .contains("No audio recording is sent to us or stored by us");
        assertThat(privacy)
                .contains("Səslə daxiletmə")
                .contains("cihazınızın öz nitq tanıma xidməti")
                .contains("Səs yazısı nə bizə göndərilir, nə də bizdə saxlanılır");

        String deletion = anonymousGet("/legal/delete-account").getBody();
        assertThat(deletion).contains("There are no voice recordings to delete");
        assertThat(deletion).contains("Səs yazıları silinmir, çünki heç vaxt bizdə olmur");
    }

    @Test
    void theAnthropicParagraphDescribesAProcessorAndTheSpaceNamesActuallySent() {
        // Two halves of one paragraph. The Data safety form is answered "Shared: No" on the grounds
        // that Anthropic processes data on the developer's behalf under the developer's
        // instructions, so the page has to SUPPORT that rather than read as a disclosure to a third
        // party — while stopping short of guarantees nobody here can verify.
        String body = anonymousGet("/legal/privacy").getBody();

        assertThat(body)
                .contains("on our behalf, as a processor, and under our instructions")
                .contains("bizim adımızdan, emalçı qismində və bizim göstərişimizlə");
        assertThat(body)
                .contains("not used to train their models")
                .contains("modellərin təlimi üçün istifadə edilmir");
        // Not an audit claim: the page says what the relationship is, not that we checked it.
        assertThat(body)
                .contains("independently audited")
                .contains("proseslərini biz özümüz yoxlaya bilmirik");

        // And what is ACTUALLY sent. ClaudeAssistant#placementSystem appends up to 20 of the
        // caller's own space names to the system prompt on EVERY remember call, whether or not the
        // sentence mentions a space — a page implying "only when you name one" understates it.
        assertThat(body)
                .contains("up to 20 of the names of your own spaces")
                .contains("20-si də göndərilir");
        assertThat(body)
                .contains("on every such request")
                .contains("hər dəfə");
    }

    @Test
    void thePrivacyPageSaysItemNamesReachTheProviderBecauseTheyNowDo() {
        // AssistantService hands the caller's own item names to the model on EVERY search, so a
        // page still saying "none of your items" would be false the moment the feature is on.
        // This project has blocked a release over exactly that kind of sentence before.
        String body = anonymousGet("/legal/privacy").getBody();

        assertThat(body)
                .contains("the names of your own items are sent as well")
                .contains("öz əşyalarınızın adları da");
        // The retraction has to be explicit, not merely absent: the old sentence promised that no
        // item ever left, and a reader who saw it once needs the contradiction spelled out.
        assertThat(body).doesNotContain("none\n    of your items");
        // What still does NOT leave is the thing the whole app is about — where you keep it.
        assertThat(body)
                .contains("never where an item is kept")
                .contains("əşyanın harada saxlandığı");
        // And the reason an invented name is harmless, which is what makes "names only" safe.
        assertThat(body)
                .contains("a name it invents finds nothing")
                .contains("uydurduğu ad heç nə tapmır");
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
