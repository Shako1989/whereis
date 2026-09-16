package az.technest.whereis.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptVersionTest {

    @Test
    void isASha256PrefixWithAStableFormat() {
        String version = PromptVersion.of("You extract structured facts.");

        assertThat(version).matches("^sha256:[0-9a-f]{12}$");
    }

    @Test
    void isDeterministicAcrossCallsAndMachines() {
        // Known vector: SHA-256("abc") starts with ba7816bf8f01, so a different JVM or OS cannot
        // produce a different value for the same prompt text.
        assertThat(PromptVersion.of("abc")).isEqualTo("sha256:ba7816bf8f01");
        assertThat(PromptVersion.of("abc")).isEqualTo(PromptVersion.of("abc"));
    }

    @Test
    void aOneCharacterPromptChangeIsADifferentVersion() {
        assertThat(PromptVersion.of("Never invent details."))
                .isNotEqualTo(PromptVersion.of("Never invent details!"));
    }

    // Two schemas that differ ONLY in instruction text the model reads.
    record SchemaA(@JsonPropertyDescription("The object that was put somewhere.") String itemName) { }

    record SchemaB(@JsonPropertyDescription("The object, in the user's own words.") String itemName) { }

    record Nested(@JsonPropertyDescription("Where it went.") List<Leaf> locations) { }

    record Leaf(@JsonPropertyDescription("Name of the place.") String name, Kind kind) { }

    enum Kind { ROOM, BOX }

    enum KindPlusOne { ROOM, BOX, SHELF }

    record NestedWiderEnum(@JsonPropertyDescription("Where it went.") List<LeafWider> locations) { }

    record LeafWider(@JsonPropertyDescription("Name of the place.") String name, KindPlusOne kind) { }

    @Test
    void aSchemaDescriptionChangeIsADifferentVersion() {
        // The whole point of folding the schema in: editing a @JsonPropertyDescription changes what
        // the model is told, so it must not keep reporting the same prompt version.
        String prompt = "You extract structured facts.";

        assertThat(PromptVersion.of(prompt, SchemaA.class))
                .isNotEqualTo(PromptVersion.of(prompt, SchemaB.class));
    }

    @Test
    void theSchemaActuallyContributesAndTheResultIsStable() {
        String prompt = "You extract structured facts.";

        assertThat(PromptVersion.of(prompt, SchemaA.class)).isNotEqualTo(PromptVersion.of(prompt));
        assertThat(PromptVersion.of(prompt, SchemaA.class))
                .isEqualTo(PromptVersion.of(prompt, SchemaA.class))
                .matches("^sha256:[0-9a-f]{12}$");
    }

    @Test
    void anAddedEnumConstantIsADifferentVersion() {
        // Enum constants are the allowed values the schema constrains the model to — an eleventh
        // LocationType changes behaviour.
        assertThat(PromptVersion.of("p", Nested.class))
                .isNotEqualTo(PromptVersion.of("p", NestedWiderEnum.class));
    }

    @Test
    void aTypeReachedTwiceIsRenderedOnceSoRepetitionCannotShiftTheDigest() {
        assertThat(PromptVersion.of("p", Nested.class, Nested.class))
                .isEqualTo(PromptVersion.of("p", Nested.class));
    }

    @Test
    void noSchemasIsTheSameAsTheTextOnlyOverload() {
        assertThat(PromptVersion.of("p")).isEqualTo(PromptVersion.of("p", new Class<?>[0]));
    }
}
