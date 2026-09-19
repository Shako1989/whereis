package az.technest.whereis.common.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The rendering that stands between a public page and a template marker.
 *
 * The page bodies live in {@code src/main/resources/legal/} and are read for real here rather than
 * stubbed: the point of these tests is that the SHIPPED files render, and a fixture would let the
 * real ones drift out from under them.
 */
class LegalPagesTest {

    private static LegalProperties filled() {
        return new LegalProperties("help@example.com", "A Person", "1 Street, City", "2026-09-19", "14", "7");
    }

    @Test
    void bothShippedPagesRenderWithNoPlaceholderLeft() {
        LegalPages pages = new LegalPages(filled());

        for (String name : new String[] {"privacy", "delete-account"}) {
            assertThat(pages.page(name))
                    .as("%s must be rendered", name)
                    .isNotNull()
                    .startsWith("<!DOCTYPE html>")
                    .doesNotContain("{{")
                    .contains("help@example.com");
        }
    }

    @Test
    void everyValueActuallyReachesThePages() {
        LegalPages pages = new LegalPages(filled());
        String both = pages.page("privacy") + pages.page("delete-account");

        // Not one assertion per page: which page carries which fact is a content decision that is
        // allowed to change. That every configured value is USED is not — an unread property is a
        // value the operator set and the reader never sees.
        assertThat(both)
                .contains("help@example.com")
                .contains("A Person")
                .contains("1 Street, City")
                .contains("2026-09-19")
                .contains("14");
    }

    @Test
    void aMissingValueFailsStartupRatherThanServingTheMarker() {
        LegalProperties blankEmail =
                new LegalProperties("  ", "A Person", "1 Street, City", "2026-09-19", "14", "7");

        assertThatThrownBy(() -> new LegalPages(blankEmail))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPPORT_EMAIL")
                .hasMessageContaining("whereis.legal");
    }

    @Test
    void aNullValueIsTreatedTheSameAsABlankOne() {
        LegalProperties noAddress =
                new LegalProperties("help@example.com", "A Person", null, "2026-09-19", "14", "7");

        assertThatThrownBy(() -> new LegalPages(noAddress))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEGAL_ADDRESS");
    }

    @Test
    void anUnknownPageNameIsNullRatherThanAFileLookup() {
        // The controller turns this into a 404. What matters here is that the name never reaches a
        // filesystem or classpath lookup, so no path can be traversed through it.
        LegalPages pages = new LegalPages(filled());

        assertThat(pages.page("../application")).isNull();
        assertThat(pages.page("terms")).isNull();
    }
}
