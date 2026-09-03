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
        // Azerbaijani dotted-İ lower-cases to i + combining dot regardless of platform locale;
        // the key folds that to a plain "i".
        assertThat(Names.normalize("İ")).isEqualTo("i");
    }

    @Test
    void normalizeFoldsAzerbaijaniDiacriticsSoOneSpellingEqualsAnother() {
        // The dedup key must not care how the diacritics were typed — otherwise "Şkaf" and "skaf"
        // become two locations for one physical wardrobe.
        assertThat(Names.normalize("Şkaf")).isEqualTo("skaf");
        assertThat(Names.normalize("skaf")).isEqualTo("skaf");
        assertThat(Names.normalize("1ci siyirmə")).isEqualTo(Names.normalize("1ci siyirme"));
        assertThat(Names.normalize("Bağ")).isEqualTo("bag");
        assertThat(Names.normalize("çanta")).isEqualTo("canta");
        assertThat(Names.normalize("Qonaq otağı")).isEqualTo("qonaq otagi");
    }

    @Test
    void cleanKeepsTheOriginalDiacriticsForDisplay() {
        // Only the KEY is folded; what the user sees is untouched.
        assertThat(Names.clean("Şkaf")).isEqualTo("Şkaf");
        assertThat(Names.clean("1ci siyirmə")).isEqualTo("1ci siyirmə");
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
