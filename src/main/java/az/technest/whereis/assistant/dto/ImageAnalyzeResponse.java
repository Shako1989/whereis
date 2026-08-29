package az.technest.whereis.assistant.dto;

import az.technest.whereis.assistant.ImageAnalysis;
import java.util.List;

/** Suggestions only — nothing is persisted until the user confirms each item explicitly. */
public record ImageAnalyzeResponse(List<ImageAnalysis.ItemSuggestion> suggestions, String note) {
}
