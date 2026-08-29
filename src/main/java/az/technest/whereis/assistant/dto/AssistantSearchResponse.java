package az.technest.whereis.assistant.dto;

import az.technest.whereis.search.dto.ItemSearchResult;
import java.util.List;

/** The answer is composed from retrieved database records only — never from AI free text. */
public record AssistantSearchResponse(String answer, List<ItemSearchResult> items) {
}
