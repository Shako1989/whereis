package az.technest.whereis.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.assistant.mock.MockAiAssistant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MockAiAssistantTest {

    private final MockAiAssistant assistant = new MockAiAssistant();

    @Test
    void parsesTheMvpJourneySentence() {
        PlacementInterpretation result =
                assistant.interpretPlacement("I put my passport in the bedroom wardrobe top drawer", List.of());

        assertThat(result.itemName()).isEqualTo("Passport");
        assertThat(result.spaceName()).isNull();
        assertThat(result.locations()).extracting(LocationSegment::name)
                .containsExactly("Bedroom", "Wardrobe", "Top Drawer");
        assertThat(result.locations()).extracting(LocationSegment::type)
                .containsExactly("ROOM", "FURNITURE", "DRAWER");
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.6);
    }

    @Test
    void detectsExplicitSpacePhrase() {
        PlacementInterpretation result =
                assistant.interpretPlacement("I put the keys in the kitchen drawer at home", List.of());

        assertThat(result.itemName()).isEqualTo("Keys");
        assertThat(result.spaceName()).isEqualTo("Home");
        assertThat(result.locations()).extracting(LocationSegment::name)
                .containsExactly("Kitchen", "Drawer");
    }

    @Test
    void parsesMovedSentence() {
        PlacementInterpretation result =
                assistant.interpretPlacement("I moved my passport to my office desk drawer", List.of());

        assertThat(result.itemName()).isEqualTo("Passport");
        assertThat(result.locations()).extracting(LocationSegment::name)
                .containsExactly("Office", "Desk", "Drawer");
    }

    @Test
    void unknownTrailingWordsBecomeAnOtherSegment() {
        PlacementInterpretation result =
                assistant.interpretPlacement("I put my charger in the garage toolchest", List.of());

        assertThat(result.locations()).extracting(LocationSegment::name)
                .containsExactly("Garage", "Toolchest");
        assertThat(result.locations().get(1).type()).isEqualTo("OTHER");
    }

    @Test
    void unparseableMessageHasZeroConfidence() {
        PlacementInterpretation result = assistant.interpretPlacement("hello there, nice weather", List.of());

        assertThat(result.confidence()).isZero();
        assertThat(result.locations()).isEmpty();
    }

    @Test
    void searchExtractsItemKeywords() {
        assertThat(assistant.interpretSearch("Where did I put my passport?", List.of()).keywords())
                .containsExactly("passport");
        assertThat(assistant.interpretSearch("Where are my travel things?", List.of()).keywords())
                .containsExactly("travel");
    }

    @Test
    void anOfferedNameThatOccursInTheSentenceComesBackAsAMatch() {
        // Exact containment on the shared lookup key — not understanding. It is what makes the
        // whole match-and-resolve path reachable from an offline integration test.
        assertThat(assistant.interpretSearch("Matkap haradadir?", List.of("Matkap", "Pasport")).matches())
                .containsExactly("Matkap");
    }

    @Test
    void containmentIsComparedOnTheFoldedKeySoDiacriticsAndCaseDoNotMatter() {
        // "Çəkic" and "cekic" are one row for Names.normalize, so they must be one match here too.
        assertThat(assistant.interpretSearch("cekic hardadir", List.of("Çəkic")).matches())
                .containsExactly("Çəkic");
    }

    @Test
    void describingAnItemFindsNothingHereAndThatIsTheHonestAnswer() {
        // The job the item list exists for is understanding, which is exactly what a rule table
        // does not have. Pinned so nobody later reads the mock's silence as the feature failing.
        assertThat(assistant.interpretSearch("divarda desik acan alet", List.of("Matkap")).matches())
                .isEmpty();
    }

    @Test
    void noListMeansNoMatches() {
        assertThat(assistant.interpretSearch("Matkap haradadir?", List.of()).matches()).isEmpty();
        assertThat(assistant.interpretSearch("Matkap haradadir?", null).matches()).isEmpty();
    }

    @Test
    void metadataIsAPinnedLiteralForBothFlows() {
        // The mock's "prompt" is Java rule tables, so its version is a literal, not a digest —
        // every IT assertion on provider/model/prompt_version depends on exactly this triple.
        AiMetadata expected = new AiMetadata("mock", "mock-rules", "mock-1");

        assertThat(assistant.metadata(AssistantMode.REMEMBER)).isEqualTo(expected);
        assertThat(assistant.metadata(AssistantMode.SEARCH)).isEqualTo(expected);
    }

    @Test
    void imageAnalysisIsDeterministic() {
        List<ImageAnalysis.ItemSuggestion> first = assistant.analyzeImage(new byte[]{1}, "image/jpeg").suggestions();
        List<ImageAnalysis.ItemSuggestion> second = assistant.analyzeImage(new byte[]{2}, "image/png").suggestions();

        assertThat(first).isNotEmpty().isEqualTo(second);
    }
}
