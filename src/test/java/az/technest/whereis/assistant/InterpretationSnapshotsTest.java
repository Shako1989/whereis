package az.technest.whereis.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import az.technest.whereis.assistant.InterpretationValidator.ValidatedPlacement;
import az.technest.whereis.location.ChainSegment;
import az.technest.whereis.location.LocationType;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class InterpretationSnapshotsTest {

    @Test
    void rawSnapshotCapsEveryFieldAndStripsControlCharacters() {
        String longName = "x".repeat(500);
        String longDescription = "d".repeat(5000);
        // A newline inside a name: exactly the kind of character that must not reach the column.
        String spaceWithControlChar = "Xalq" + '\n' + "lar";
        List<LocationSegment> tooMany = IntStream.range(0, 25)
                .mapToObj(i -> new LocationSegment("Segment " + i, "WEIRD_TYPE"))
                .toList();

        InterpretationSnapshot snapshot = InterpretationSnapshots.fromRaw(
                new PlacementInterpretation(longName, longDescription, spaceWithControlChar, tooMany, 0.4));

        assertThat(snapshot.validated()).isFalse();
        assertThat(snapshot.itemName()).hasSize(InterpretationSnapshots.MAX_NAME_LENGTH);
        assertThat(snapshot.itemDescription()).hasSize(InterpretationSnapshots.MAX_DESCRIPTION_LENGTH);
        assertThat(snapshot.spaceName()).isEqualTo("Xalqlar");
        assertThat(snapshot.locations()).hasSize(InterpretationSnapshots.MAX_LOCATIONS);
        assertThat(snapshot.locations().getFirst().name()).isEqualTo("Segment 0");
        // An unknown type survives verbatim: that is precisely what a NOT_UNDERSTOOD row must show.
        assertThat(snapshot.locations().getFirst().type()).isEqualTo("WEIRD_TYPE");
        assertThat(snapshot.keywords()).isNull();
        assertThat(snapshot.usedFallback()).isNull();
    }

    @Test
    void rawSnapshotToleratesNullsEverywhere() {
        assertThat(InterpretationSnapshots.fromRaw(null).validated()).isFalse();

        InterpretationSnapshot snapshot = InterpretationSnapshots.fromRaw(
                new PlacementInterpretation(null, null, null, Collections.singletonList(null), null));

        assertThat(snapshot.itemName()).isNull();
        assertThat(snapshot.locations()).singleElement()
                .satisfies(segment -> {
                    assertThat(segment.name()).isNull();
                    assertThat(segment.type()).isNull();
                });
    }

    @Test
    void confidenceIsNullForAnythingThatIsNotAFiniteNumberInRange() {
        // BigDecimal.valueOf(NaN) throws, and the CHECK rejects values outside [0,1] — both must
        // become NULL instead of a 500 or a constraint violation.
        assertThat(InterpretationSnapshots.confidenceOf(Double.NaN)).isNull();
        assertThat(InterpretationSnapshots.confidenceOf(Double.POSITIVE_INFINITY)).isNull();
        assertThat(InterpretationSnapshots.confidenceOf(Double.NEGATIVE_INFINITY)).isNull();
        assertThat(InterpretationSnapshots.confidenceOf(-0.5)).isNull();
        assertThat(InterpretationSnapshots.confidenceOf(7.3)).isNull();
        assertThat(InterpretationSnapshots.confidenceOf(null)).isNull();
    }

    @Test
    void confidenceIsNormalizedToThreeDecimals() {
        assertThat(InterpretationSnapshots.confidenceOf(0.9)).isEqualByComparingTo("0.900");
        assertThat(InterpretationSnapshots.confidenceOf(0.93456)).isEqualByComparingTo("0.935");
        assertThat(InterpretationSnapshots.confidenceOf(0.0)).isEqualByComparingTo("0.000");
        assertThat(InterpretationSnapshots.confidenceOf(1.0)).isEqualByComparingTo("1.000");
        assertThat(InterpretationSnapshots.confidenceOf(0.9).scale()).isEqualTo(3);
    }

    @Test
    void validatedSnapshotCarriesParsedTypesAndTheValidatedFlag() {
        ValidatedPlacement placement = new ValidatedPlacement("Passport", null, "Home",
                List.of(new ChainSegment("Bedroom", LocationType.ROOM), new ChainSegment("Şkaf", null)));

        InterpretationSnapshot snapshot = InterpretationSnapshots.fromValidated(placement, null, null);

        assertThat(snapshot.validated()).isTrue();
        assertThat(snapshot.itemName()).isEqualTo("Passport");
        assertThat(snapshot.spaceName()).isEqualTo("Home");
        assertThat(snapshot.locations())
                .extracting(InterpretationSnapshot.Segment::name, InterpretationSnapshot.Segment::type)
                .containsExactly(tuple("Bedroom", "ROOM"), tuple("Şkaf", "OTHER"));
        assertThat(snapshot.keywords()).isNull();
    }

    @Test
    void searchSnapshotCarriesOnlyKeywordsAndTheFallbackFlag() {
        InterpretationSnapshot answered = InterpretationSnapshots.forSearch(
                List.of("passport", "keys", "jacket", "phone", "wallet", "sixth", "seventh"),
                List.of(), 0, true);

        assertThat(answered.validated()).isTrue();
        assertThat(answered.keywords()).hasSize(InterpretationSnapshots.MAX_KEYWORDS)
                .startsWith("passport", "keys");
        assertThat(answered.usedFallback()).isTrue();
        assertThat(answered.itemName()).isNull();
        assertThat(answered.locations()).isNull();
        assertThat(answered.rawKeywords()).isNull();

        InterpretationSnapshot notUnderstood = InterpretationSnapshots.forSearchNotUnderstood(List.of("!", "?"));

        assertThat(notUnderstood.validated()).isFalse();
        assertThat(notUnderstood.keywords()).isEmpty();
        assertThat(notUnderstood.rawKeywords()).containsExactly("!", "?");
        assertThat(notUnderstood.usedFallback()).isNull();
    }

    @Test
    void theSpaceNamesOfferedToTheModelAreRecordedAndCapped() {
        // Incident 2026-09-16 #1: the model answered "Work" while the user meant the listed space
        // "Xalqlar". Without this field the row cannot say whether "Xalqlar" was ever offered.
        List<String> offered = IntStream.range(0, 30).mapToObj(i -> "Space " + i).toList();

        InterpretationSnapshot snapshot = InterpretationSnapshots.fromRaw(
                new PlacementInterpretation("Boya", null, "Work", List.of(), 0.9), offered);

        assertThat(snapshot.offeredSpaces())
                .hasSize(InterpretationSnapshots.MAX_OFFERED_SPACES)
                .startsWith("Space 0");
    }

    @Test
    void anEmptySpaceListLeavesTheKeyOutEntirely() {
        InterpretationSnapshot snapshot = InterpretationSnapshots.fromRaw(
                new PlacementInterpretation("Boya", null, "Work", List.of(), 0.9), List.of());

        assertThat(snapshot.offeredSpaces()).isNull();
        assertThat(InterpretationSnapshots.forFailure(List.of())).isNull();
        assertThat(InterpretationSnapshots.forFailure(List.of("Home")).offeredSpaces())
                .containsExactly("Home");
    }

    @Test
    void aValidatedSnapshotKeepsTheModelsPreValidationAnswerBeside() {
        // The validator degrades an unsafe space name to null. A row carrying only the validated
        // view would read as "the model named no space", which is the opposite of what happened.
        PlacementInterpretation raw = new PlacementInterpretation("Rasvoritel", null,
                "Work/Xalqlar", List.of(new LocationSegment("Karobka", "BOX")), 0.91);
        ValidatedPlacement placement = new ValidatedPlacement("Rasvoritel", null, null,
                List.of(new ChainSegment("Karobka", LocationType.BOX)));

        InterpretationSnapshot snapshot =
                InterpretationSnapshots.fromValidated(placement, raw, List.of("Xalqlar", "Work"));

        assertThat(snapshot.validated()).isTrue();
        assertThat(snapshot.spaceName()).isNull();
        assertThat(snapshot.offeredSpaces()).containsExactly("Xalqlar", "Work");
        assertThat(snapshot.raw()).isNotNull();
        assertThat(snapshot.raw().spaceName()).isEqualTo("Work/Xalqlar");
        assertThat(snapshot.raw().validated()).isFalse();
        // One level only: the nested snapshot carries neither a nested raw nor the request context.
        assertThat(snapshot.raw().raw()).isNull();
        assertThat(snapshot.raw().offeredSpaces()).isNull();
    }

    @Test
    void aValidatedSnapshotWithoutRawOutputStillWorks() {
        ValidatedPlacement placement = new ValidatedPlacement("Passport", null, "Home", List.of());

        InterpretationSnapshot snapshot = InterpretationSnapshots.fromValidated(placement, null, null);

        assertThat(snapshot.raw()).isNull();
        assertThat(snapshot.confidence()).isNull();
    }
}
