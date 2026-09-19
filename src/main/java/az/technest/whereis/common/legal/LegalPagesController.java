package az.technest.whereis.common.legal;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two pages Google Play links to, served rendered rather than as static files.
 *
 * <p>Both URL forms are answered — {@code /legal/privacy} and {@code /legal/privacy.html} — because
 * the extension-less form is what goes on the store listing while the {@code .html} form is what a
 * link checker or an old bookmark may ask for.
 *
 * <p>An unknown name is a bare 404 with no body, deliberately: this is a web page, not an API
 * endpoint, and an {@code ApiError} envelope here would be answering an HTML request in the API's
 * vocabulary. It is also not a hole — the path variable only ever selects from a fixed map built at
 * startup and is never used to resolve a file.
 */
@RestController
@RequiredArgsConstructor
public class LegalPagesController {

    private final LegalPages pages;

    @GetMapping(value = {"/legal/{name}", "/legal/{name}.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String name) {
        String html = pages.page(name);
        return html == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(html);
    }
}
