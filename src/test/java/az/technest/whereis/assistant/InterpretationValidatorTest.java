package az.technest.whereis.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.assistant.InterpretationValidator.ValidatedPlacement;
import az.technest.whereis.location.LocationType;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InterpretationValidatorTest {

    private final InterpretationValidator validator = new InterpretationValidator();

    private static PlacementInterpretation valid() {
        return new PlacementInterpretation("Passport", null, "Home",
                List.of(new LocationSegment("Bedroom", "ROOM"), new LocationSegment("Top Drawer", "DRAWER")),
                0.9);
    }

    @Test
    void validInterpretationPasses() {
        Optional<ValidatedPlacement> result = validator.validatePlacement(valid());

        assertThat(result).isPresent();
        assertThat(result.get().itemName()).isEqualTo("Passport");
        assertThat(result.get().segments()).hasSize(2);
        assertThat(result.get().segments().get(1).type()).isEqualTo(LocationType.DRAWER);
    }

    @Test
    void lowConfidenceIsRejected() {
        PlacementInterpretation interp = new PlacementInterpretation("Passport", null, null,
                List.of(new LocationSegment("Bedroom", "ROOM")), 0.3);

        assertThat(validator.validatePlacement(interp)).isEmpty();
    }

    @Test
    void nanAndOutOfRangeConfidenceFailClosed() {
        List<LocationSegment> locations = List.of(new LocationSegment("Bedroom", "ROOM"));
        assertThat(validator.validatePlacement(
                new PlacementInterpretation("Keys", null, null, locations, Double.NaN))).isEmpty();
        assertThat(validator.validatePlacement(
                new PlacementInterpretation("Keys", null, null, locations, 1.5))).isEmpty();
        assertThat(validator.validatePlacement(
                new PlacementInterpretation("Keys", null, null, locations, Double.POSITIVE_INFINITY))).isEmpty();
    }

    @Test
    void imageSuggestionsAreSanitizedAndCapped() {
        List<ImageAnalysis.ItemSuggestion> raw = new java.util.ArrayList<>();
        raw.add(new ImageAnalysis.ItemSuggestion("USB-C Cable", "Electronics"));
        raw.add(new ImageAnalysis.ItemSuggestion("### drop table;", "Electronics"));
        raw.add(new ImageAnalysis.ItemSuggestion("HDMI Cable", "%%%bad%%%"));
        for (int i = 0; i < 20; i++) {
            raw.add(new ImageAnalysis.ItemSuggestion("Battery " + i, null));
        }

        List<ImageAnalysis.ItemSuggestion> validated =
                validator.validateSuggestions(new ImageAnalysis(raw));

        assertThat(validated).hasSize(10);
        assertThat(validated.getFirst().name()).isEqualTo("USB-C Cable");
        assertThat(validated).noneMatch(s -> s.name().contains("#"));
        // Unsafe category degrades to null rather than dropping the suggestion.
        assertThat(validated.get(1).name()).isEqualTo("HDMI Cable");
        assertThat(validated.get(1).category()).isNull();
    }

    @Test
    void chainDeeperThanSixIsRejected() {
        List<LocationSegment> tooDeep = Collections.nCopies(7, new LocationSegment("Box", "BOX"));
        PlacementInterpretation interp = new PlacementInterpretation("Keys", null, null, tooDeep, 0.9);

        assertThat(validator.validatePlacement(interp)).isEmpty();
    }

    @Test
    void garbageLocationNameIsRejected() {
        PlacementInterpretation interp = new PlacementInterpretation("Keys", null, null,
                List.of(new LocationSegment("### drop table;", "ROOM")), 0.9);

        assertThat(validator.validatePlacement(interp)).isEmpty();
    }

    @Test
    void missingItemNameIsRejected() {
        PlacementInterpretation interp = new PlacementInterpretation("  ", null, null,
                List.of(new LocationSegment("Bedroom", "ROOM")), 0.9);

        assertThat(validator.validatePlacement(interp)).isEmpty();
    }

    @Test
    void unknownTypeDegradesToOther() {
        PlacementInterpretation interp = new PlacementInterpretation("Keys", null, null,
                List.of(new LocationSegment("Nook", "SPACESHIP")), 0.9);

        assertThat(validator.validatePlacement(interp).orElseThrow().segments().getFirst().type())
                .isEqualTo(LocationType.OTHER);
    }

    @Test
    void invalidSpaceNameDegradesToNullNotRejection() {
        PlacementInterpretation interp = new PlacementInterpretation("Keys", null, "!!!###",
                List.of(new LocationSegment("Bedroom", "ROOM")), 0.9);

        Optional<ValidatedPlacement> result = validator.validatePlacement(interp);
        assertThat(result).isPresent();
        assertThat(result.get().spaceName()).isNull();
    }

    @Test
    void keywordsAreCleanedDedupedAndCapped() {
        SearchInterpretation interp = new SearchInterpretation(
                List.of("Passport", "passport", "x", "%%%", "cable", "charger", "keys", "wallet", "phone"),
                null);

        assertThat(validator.validateKeywords(interp))
                .containsExactly("passport", "cable", "charger", "keys", "wallet");
    }

    @Test
    void matchesAreNormalizedToTheLookupKeyDedupedAndCapped() {
        // Normalized, not cleaned: these are about to be compared against items.normalized_name,
        // so "Matkap" and "matkap" are one row and must collapse to one entry.
        SearchInterpretation interp = new SearchInterpretation(null, List.of(
                "Matkap", "matkap", "Çəkic", "a", "b", "c", "d", "e", "f", "g", "h", "eleventh"));

        assertThat(validator.validateMatches(interp))
                .containsExactly("matkap", "cekic", "a", "b", "c", "d", "e", "f", "g", "h");
    }

    @Test
    void aSingleLetterSurvivesWhereAKeywordWouldNot() {
        // A keyword needs two characters because it feeds a FUZZY search, where one letter would
        // drag half the inventory back. A match is an exact lookup, and "X" is a name people use.
        assertThat(validator.validateMatches(new SearchInterpretation(null, List.of("X"))))
                .containsExactly("x");
    }

    @Test
    void aModelThatAnswersWithASentenceOrAnInjectionHasItDropped() {
        SearchInterpretation interp = new SearchInterpretation(null, List.of(
                "'; drop table items; --",
                "<script>alert(1)</script>",
                "Matkap"));

        // Nothing reaches the database but the one entry shaped like a name — and even that is
        // only a lookup key, resolved against this caller's own rows.
        assertThat(validator.validateMatches(interp)).containsExactly("matkap");
    }

    @Test
    void anOverlongMatchIsDroppedRatherThanTruncated() {
        // Truncating would invent a name the user never had and might accidentally resolve.
        String tooLong = "m".repeat(121);
        assertThat(validator.validateMatches(new SearchInterpretation(null, List.of(tooLong)))).isEmpty();
        assertThat(validator.validateMatches(new SearchInterpretation(null, List.of("m".repeat(120)))))
                .hasSize(1);
    }

    @Test
    void nullsAreHandled() {
        assertThat(validator.validatePlacement(null)).isEmpty();
        assertThat(validator.validateKeywords(null)).isEmpty();
        assertThat(validator.validateKeywords(new SearchInterpretation(null, null))).isEmpty();
        assertThat(validator.validateMatches(null)).isEmpty();
        assertThat(validator.validateMatches(new SearchInterpretation(null, null))).isEmpty();
    }
}
