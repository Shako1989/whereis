package az.technest.whereis.assistant;

import java.util.List;

/** Raw, untrusted AI image analysis. Suggestions are never persisted without user confirmation. */
public record ImageAnalysis(List<ItemSuggestion> suggestions) {

    public record ItemSuggestion(String name, String category) {
    }
}
