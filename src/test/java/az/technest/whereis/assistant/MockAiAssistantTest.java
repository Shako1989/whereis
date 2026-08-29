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
                assistant.interpretPlacement("I put my passport in the bedroom wardrobe top drawer");

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
                assistant.interpretPlacement("I put the keys in the kitchen drawer at home");

        assertThat(result.itemName()).isEqualTo("Keys");
        assertThat(result.spaceName()).isEqualTo("Home");
        assertThat(result.locations()).extracting(LocationSegment::name)
                .containsExactly("Kitchen", "Drawer");
    }

    @Test
    void parsesMovedSentence() {
        PlacementInterpretation result =
                assistant.interpretPlacement("I moved my passport to my office desk drawer");

        assertThat(result.itemName()).isEqualTo("Passport");
        assertThat(result.locations()).extracting(LocationSegment::name)
                .containsExactly("Office", "Desk", "Drawer");
    }

    @Test
    void unknownTrailingWordsBecomeAnOtherSegment() {
        PlacementInterpretation result =
                assistant.interpretPlacement("I put my charger in the garage toolchest");

        assertThat(result.locations()).extracting(LocationSegment::name)
                .containsExactly("Garage", "Toolchest");
        assertThat(result.locations().get(1).type()).isEqualTo("OTHER");
    }

    @Test
    void unparseableMessageHasZeroConfidence() {
        PlacementInterpretation result = assistant.interpretPlacement("hello there, nice weather");

        assertThat(result.confidence()).isZero();
        assertThat(result.locations()).isEmpty();
    }

    @Test
    void searchExtractsItemKeywords() {
        assertThat(assistant.interpretSearch("Where did I put my passport?").keywords())
                .containsExactly("passport");
        assertThat(assistant.interpretSearch("Where are my travel things?").keywords())
                .containsExactly("travel");
    }

    @Test
    void imageAnalysisIsDeterministic() {
        List<ImageAnalysis.ItemSuggestion> first = assistant.analyzeImage(new byte[]{1}, "image/jpeg").suggestions();
        List<ImageAnalysis.ItemSuggestion> second = assistant.analyzeImage(new byte[]{2}, "image/png").suggestions();

        assertThat(first).isNotEmpty().isEqualTo(second);
    }
}
