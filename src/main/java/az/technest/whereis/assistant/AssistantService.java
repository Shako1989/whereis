package az.technest.whereis.assistant;

import az.technest.whereis.assistant.InterpretationValidator.ValidatedPlacement;
import az.technest.whereis.assistant.dto.AssistantSearchResponse;
import az.technest.whereis.assistant.dto.ImageAnalyzeResponse;
import az.technest.whereis.assistant.dto.RememberResponse;
import az.technest.whereis.assistant.dto.RememberResponse.SpaceOption;
import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.error.NotFoundException;
import az.technest.whereis.common.util.Names;
import az.technest.whereis.search.SearchService;
import az.technest.whereis.search.dto.ItemSearchResult;
import az.technest.whereis.space.Space;
import az.technest.whereis.space.SpaceRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Orchestrates the assistant flows. Fixed pipeline:
 * user message -> AI interpretation -> validation -> entity resolution ->
 * confirmation when ambiguous -> database operation.
 * The AI never mutates anything; the database is the only source of truth.
 */
@Service
@RequiredArgsConstructor
public class AssistantService {

    private static final int MAX_ANSWER_ITEMS = 10;

    private final AiAssistant aiAssistant;
    private final InterpretationValidator validator;
    private final SpaceRepository spaceRepository;
    private final PlacementExecutor executor;
    private final SearchService searchService;

    public RememberResponse remember(UUID userId, String message, UUID chosenSpaceId) {
        String sanitized = sanitize(message, 1000);
        // Read first, then call the provider: the user's own space names let it map a mention in
        // any language ("evdə") onto a space that already exists ("Home"). Names only — never ids,
        // and never another user's rows. Deliberately outside any transaction: no AI call may sit
        // inside one.
        List<Space> spaces = spaceRepository.findAllByUserIdOrderByNameAsc(userId);
        List<String> knownSpaceNames = spaces.stream().map(Space::getName).toList();
        PlacementInterpretation interpretation =
                aiAssistant.interpretPlacement(sanitized, knownSpaceNames);
        Optional<ValidatedPlacement> validated = validator.validatePlacement(interpretation);
        if (validated.isEmpty()) {
            return RememberResponse.notUnderstood(
                    "I couldn't understand what was placed where. Try something like "
                            + "\"I put my passport in the bedroom wardrobe top drawer\".");
        }
        ValidatedPlacement placement = validated.get();
        Optional<Space> space = chosenSpaceId != null
                ? Optional.of(requireOwnSpace(userId, chosenSpaceId))
                : resolveSpace(userId, spaces, placement.spaceName());
        if (space.isEmpty()) {
            List<SpaceOption> candidates = spaces.stream()
                    .map(s -> new SpaceOption(s.getId(), s.getName()))
                    .toList();
            // Three genuinely different situations, so three different messages — a single
            // "pick one" wording is misleading when the user actually named a space that simply
            // does not exist yet (e.g. "işdə"/"at work" with no Office space).
            String named = placement.spaceName();
            String hint;
            if (candidates.isEmpty()) {
                hint = "You have no spaces yet. Create one first (for example \"Home\"), then try again.";
            } else if (named != null) {
                hint = "You don't have a space for \"" + named + "\" yet. Create it first, or resend "
                        + "with the spaceId of one of your existing spaces.";
            } else {
                hint = "I couldn't tell which space you meant. Resend with the spaceId of one of these.";
            }
            return RememberResponse.needsConfirmation(hint, candidates);
        }
        PlacementExecutor.ExecutionResult result =
                executor.place(userId, space.get().getId(), placement, "Registered via assistant");
        return RememberResponse.created(result.item(), result.createdLocations(),
                "Saved. " + result.item().name() + " is in "
                        + String.join(" > ", result.item().locationPath()) + ".");
    }

    public AssistantSearchResponse search(UUID userId, String query) {
        String sanitized = sanitize(query, 500);
        List<String> keywords;
        try {
            keywords = validator.validateKeywords(aiAssistant.interpretSearch(sanitized));
        } catch (AiAssistantException e) {
            // Search must degrade gracefully when the provider is down.
            keywords = List.of();
        }
        if (keywords.isEmpty()) {
            String fallback = Names.normalize(sanitized);
            keywords = fallback != null && fallback.length() >= 2 ? List.of(fallback) : List.of();
        }
        Map<UUID, ItemSearchResult> merged = new LinkedHashMap<>();
        for (String keyword : keywords.subList(0, Math.min(keywords.size(), 3))) {
            for (ItemSearchResult result : searchService.search(userId, keyword, MAX_ANSWER_ITEMS)) {
                merged.putIfAbsent(result.id(), result);
                if (merged.size() >= MAX_ANSWER_ITEMS) {
                    break;
                }
            }
        }
        List<ItemSearchResult> items = new ArrayList<>(merged.values());
        return new AssistantSearchResponse(composeAnswer(items), items);
    }

    public ImageAnalyzeResponse analyzeImage(UUID userId, MultipartFile file) {
        az.technest.whereis.storage.ImageSignatures.validate(file);
        byte[] content;
        try {
            content = file.getBytes();
        } catch (java.io.IOException e) {
            throw new BadRequestException(ErrorCode.VALIDATION_ERROR, "Could not read the uploaded image");
        }
        ImageAnalysis analysis = aiAssistant.analyzeImage(content, file.getContentType());
        List<ImageAnalysis.ItemSuggestion> suggestions = validator.validateSuggestions(analysis);
        return new ImageAnalyzeResponse(suggestions,
                "Suggestions only — nothing has been saved. Create the items you confirm via POST /api/v1/items.");
    }

    /** The answer is built exclusively from retrieved records; no AI text reaches the user here. */
    private static String composeAnswer(List<ItemSearchResult> items) {
        if (items.isEmpty()) {
            return "I couldn't find anything matching that. Try different words, or register the item first.";
        }
        ItemSearchResult top = items.getFirst();
        String answer = "Your " + top.name() + " is in " + String.join(" > ", top.locationPath()) + ".";
        if (items.size() > 1) {
            answer += " I also found " + (items.size() - 1) + " other matching item(s).";
        }
        return answer;
    }

    /**
     * An explicitly chosen space still has to be this user's. A miss is a 404 rather than a 403 —
     * the same rule as everywhere else, so an id cannot be probed for existence.
     */
    private Space requireOwnSpace(UUID userId, UUID spaceId) {
        return spaceRepository.findByIdAndUserId(spaceId, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.SPACE_NOT_FOUND, "Space not found"));
    }

    /** Resolves against the rows already read for this request; the AI never supplies an id. */
    private Optional<Space> resolveSpace(UUID userId, List<Space> spaces, String spaceName) {
        if (spaceName != null) {
            return spaceRepository.findByUserIdAndNormalizedName(userId, Names.normalize(spaceName));
        }
        // Only when the intent is unambiguous: exactly one space to choose from.
        return spaces.size() == 1 ? Optional.of(spaces.getFirst()) : Optional.empty();
    }

    private static String sanitize(String message, int maxLength) {
        String cleaned = Names.clean(message);
        if (cleaned != null) {
            cleaned = cleaned.replaceAll("\\p{Cntrl}", "");
        }
        if (cleaned == null || cleaned.isBlank()) {
            throw new BadRequestException(ErrorCode.VALIDATION_ERROR, "Message must not be empty");
        }
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }
}
