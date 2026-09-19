package az.technest.whereis.plan.play;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * THE GOLDEN VECTORS. The Android client computes this same value for
 * {@code BillingFlowParams.setObfuscatedAccountId} and the server recomputes it from the JWT
 * subject; any canonicalisation slip on either side makes every purchase fail the cross-check with
 * no server-side recovery. These vectors must be copied verbatim into the Android repository's own
 * test — that is the only thing that makes the two implementations provably identical.
 */
class PlayAccountHashTest {

    private static final UUID NIL = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final UUID SAMPLE = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");

    @Test
    void theGoldenVectors() {
        assertThat(PlayAccountHash.of(NIL))
                .isEqualTo("12b9377cbe7e5c94e8a70d9d23929523d14afa954793130f8a3959c7b849aca8");
        assertThat(PlayAccountHash.of(SAMPLE))
                .isEqualTo("16362f566387b3cf5a6e92fb0a986c76ca20eb3a0c12cbdfbd0b29501e0c18df");
    }

    @Test
    void theOutputIsAlwaysSixtyFourLowercaseHexCharacters() {
        for (int i = 0; i < 20; i++) {
            String hash = PlayAccountHash.of(UUID.randomUUID());
            assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        }
    }

    @Test
    void theInputIsTheCanonicalLowercaseHyphenatedForm() {
        // UUID.toString() is always lowercase and hyphenated, and UUID.fromString accepts an
        // uppercase form and normalises it — so a client that upper-cased the id before parsing
        // still produces the same bytes. What it must NOT do is hash the raw string it received.
        assertThat(PlayAccountHash.of(UUID.fromString("3F2504E0-4F89-41D3-9A0C-0305E82C3301")))
                .isEqualTo(PlayAccountHash.of(SAMPLE));
        assertThat(PlayAccountHash.of("3f2504e04f8941d39a0c0305e82c3301"))
                .isNotEqualTo(PlayAccountHash.of(SAMPLE));
    }

    @Test
    void comparisonIsCaseInsensitiveOnTheHexButNeverMatchesAnAbsentValue() {
        assertThat(PlayAccountHash.matches(PlayAccountHash.of(SAMPLE), SAMPLE)).isTrue();
        // A client that upper-cases its digest still matches: that costs nothing and removes one
        // whole class of "every purchase 409s forever".
        assertThat(PlayAccountHash.matches(PlayAccountHash.of(SAMPLE).toUpperCase(java.util.Locale.ROOT), SAMPLE))
                .isTrue();
        assertThat(PlayAccountHash.matches(PlayAccountHash.of(NIL), SAMPLE)).isFalse();
        assertThat(PlayAccountHash.matches(null, SAMPLE)).isFalse();
        assertThat(PlayAccountHash.matches(SAMPLE.toString(), SAMPLE)).isFalse();
    }
}
