package az.technest.whereis.common;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.common.util.Names;
import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void normalizeTrimsCollapsesAndLowercases() {
        assertThat(Names.normalize("  Top   DRAWER ")).isEqualTo("top drawer");
    }

    @Test
    void normalizeIsIdempotent() {
        String once = Names.normalize("  Blue  Box ");
        assertThat(Names.normalize(once)).isEqualTo(once);
    }

    @Test
    void normalizeAppliesNfkc() {
        // The "ﬁ" ligature decomposes to "fi" under NFKC.
        assertThat(Names.normalize("oﬃce")).isEqualTo("office");
    }

    @Test
    void normalizeUsesRootLocaleConsistently() {
        // Must not depend on the default locale (Azerbaijani/Turkish dotted-i trap):
        // result must be identical regardless of platform locale.
        assertThat(Names.normalize("WARDROBE")).isEqualTo("wardrobe");
        assertThat(Names.normalize("İ")).isEqualTo("İ".toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void cleanPreservesCasing() {
        assertThat(Names.clean("  Top   Drawer ")).isEqualTo("Top Drawer");
    }

    @Test
    void nullSafe() {
        assertThat(Names.clean(null)).isNull();
        assertThat(Names.normalize(null)).isNull();
    }
}
