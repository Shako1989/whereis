package az.technest.whereis.assistant;

import az.technest.whereis.assistant.InterpretationValidator.ValidatedPlacement;
import az.technest.whereis.assistant.dto.AssistantSearchResponse;
import az.technest.whereis.assistant.dto.ImageAnalyzeResponse;
import az.technest.whereis.assistant.dto.RememberResponse;
import az.technest.whereis.assistant.dto.RememberResponse.SpaceOption;
import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.error.NotFoundException;
import az.technest.whereis.common.util.Names;
import az.technest.whereis.search.SearchService;
import az.technest.whereis.search.dto.ItemSearchResult;
import az.technest.whereis.space.Space;
import az.technest.whereis.space.SpaceRepository;
import az.technest.whereis.storage.ImageSignatures;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Orchestrates the assistant flows. Fixed pipeline:
 * user message -> AI interpretation -> validation -> entity resolution ->
 * confirmation when ambiguous -> database operation.
 * The AI never mutates anything; the database is the only source of truth.
 *
 * <p>Provenance (V8): every request that reaches {@link #remember} or {@link #search} past
 * {@code sanitize} leaves exactly one {@code assistant_messages} row, written by
 * {@link AssistantMessageService#record} in its own REQUIRES_NEW transaction strictly AFTER the flow
 * has finished — after the provider returned or threw, and after {@link PlacementExecutor#place}
 * has committed (CREATED) or rolled back (FAILED). This class has no {@code @Transactional}
 * anywhere, so the plain call after the executor's proxy returns IS the after-commit point; no
 * {@code TransactionSynchronization} is involved. The row can never cost the user their item or
 * their answer: a recorder failure is swallowed at WARN in {@link #recordQuietly}, without the
 * sentence.
 *
 * <p>Two paths deliberately record nothing: a {@code sanitize} rejection (there is no sentence worth
 * keeping and no outcome meaning "never reached the model") and a foreign {@code spaceId} 404 (a
 * client error, not an interpretation). {@code analyzeImage} has no sentence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantService {

    static final String PLACEMENT_NOTE = "Registered via assistant";
    private static final int MAX_ANSWER_ITEMS = 10;
    private static final int MAX_SEARCH_KEYWORDS = 3;
    private static final int MAX_REMEMBER_LENGTH = 1000;
    private static final int MAX_SEARCH_LENGTH = 500;

    private final AiAssistant aiAssistant;
    private final InterpretationValidator validator;
    private final SpaceRepository spaceRepository;
    private final PlacementExecutor executor;
    private final SearchService searchService;
    private final AssistantMessageService messages;

    public RememberResponse remember(UUID userId, String message, UUID chosenSpaceId) {
        String sanitized = sanitize(message, MAX_REMEMBER_LENGTH);
        // Read first, then call the provider: the user's own space names let it map a mention in
        // any language ("evdə") onto a space that already exists ("Home"). Names only — never ids,
        // and never another user's rows. Deliberately outside any transaction: no AI call may sit
        // inside one.
        List<Space> spaces = spaceRepository.findAllByUserIdOrderByNameAsc(userId);
        List<String> knownSpaceNames = spaces.stream().map(Space::getName).toList();
        // Captured BEFORE the provider call, so even a FAILED row knows provider/model/prompt.
        AiMetadata ai = aiAssistant.metadata(AssistantMode.REMEMBER);
        PlacementInterpretation interpretation = interpretPlacement(userId, sanitized, knownSpaceNames, ai);
        Optional<ValidatedPlacement> validated = validator.validatePlacement(interpretation);
        if (validated.isEmpty()) {
            // The one outcome where the RAW output matters: the point is seeing what the model said.
            recordQuietly(rememberDraft(userId, sanitized, ai,
                            InterpretationSnapshots.fromRaw(interpretation, knownSpaceNames)),
                    AssistantMessageResult.notUnderstood());
            return RememberResponse.notUnderstood(
                    "I couldn't understand what was placed where. Try something like "
                            + "\"I put my passport in the bedroom wardrobe top drawer\".");
        }
        ValidatedPlacement placement = validated.get();
        AssistantMessageDraft draft = rememberDraft(userId, sanitized, ai,
                InterpretationSnapshots.fromValidated(placement, interpretation, knownSpaceNames));
        Optional<Space> space = chosenSpaceId != null
                ? Optional.of(requireOwnSpace(userId, chosenSpaceId))
                : resolveSpace(userId, spaces, placement.spaceName());
        if (space.isEmpty()) {
            // Zero DOMAIN writes: the message row is the only thing written, in its own transaction.
            recordQuietly(draft, AssistantMessageResult.needsConfirmation());
            return RememberResponse.needsConfirmation(confirmationHint(spaces, placement.spaceName()),
                    candidatesOf(spaces));
        }
        return place(space.get().getId(), placement, draft);
    }

    public AssistantSearchResponse search(UUID userId, String query) {
        String sanitized = sanitize(query, MAX_SEARCH_LENGTH);
        AiMetadata ai = aiAssistant.metadata(AssistantMode.SEARCH);
        SearchInterpretation interpretation = null;
        AiAssistantException providerFailure = null;
        List<String> keywords;
        try {
            interpretation = aiAssistant.interpretSearch(sanitized);
            keywords = validator.validateKeywords(interpretation);
        } catch (AiAssistantException e) {
            // Search must degrade gracefully when the provider is down.
            providerFailure = e;
            keywords = List.of();
        }
        boolean usedFallback = false;
        if (keywords.isEmpty()) {
            String fallback = Names.normalize(sanitized);
            keywords = fallback != null && fallback.length() >= 2 ? List.of(fallback) : List.of();
            usedFallback = !keywords.isEmpty();
        }
        List<ItemSearchResult> items = searchAll(userId, keywords);
        recordSearch(userId, sanitized, ai, interpretation, keywords, usedFallback, providerFailure);
        return new AssistantSearchResponse(composeAnswer(items), items);
    }

    public ImageAnalyzeResponse analyzeImage(UUID userId, MultipartFile file) {
        ImageSignatures.validate(file);
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException(ErrorCode.VALIDATION_ERROR, "Could not read the uploaded image");
        }
        ImageAnalysis analysis = aiAssistant.analyzeImage(content, file.getContentType());
        List<ImageAnalysis.ItemSuggestion> suggestions = validator.validateSuggestions(analysis);
        return new ImageAnalyzeResponse(suggestions,
                "Suggestions only — nothing has been saved. Create the items you confirm via POST /api/v1/items.");
    }

    private PlacementInterpretation interpretPlacement(UUID userId, String sanitized, List<String> knownSpaceNames,
                                                       AiMetadata ai) {
        try {
            return aiAssistant.interpretPlacement(sanitized, knownSpaceNames);
        } catch (RuntimeException e) {
            // Record-then-rethrow, not a swallow: the HTTP behaviour stays byte-identical, and the
            // FAILED row (interpretation NULL) is the only trace the request ever existed.
            recordQuietly(rememberDraft(userId, sanitized, ai, InterpretationSnapshots.forFailure(knownSpaceNames)),
                    AssistantMessageResult.failed(errorCodeOf(e)));
            throw e;
        }
    }

    /**
     * The executor's transactional proxy has committed before it returns and rolled back before it
     * throws, so both records below happen strictly outside the domain transaction: the CREATED call
     * is the after-commit point, and the FAILED call is the only trace of a rolled-back placement.
     */
    private RememberResponse place(UUID spaceId, ValidatedPlacement placement, AssistantMessageDraft draft) {
        PlacementExecutor.ExecutionResult result;
        try {
            result = executor.place(draft.userId(), spaceId, placement, PLACEMENT_NOTE);
        } catch (RuntimeException e) {
            recordQuietly(draft, AssistantMessageResult.failed(errorCodeOf(e), spaceId));
            throw e;
        }
        recordQuietly(draft, AssistantMessageResult.created(result.item().id(), spaceId));
        return RememberResponse.created(result.item(), result.createdLocations(),
                "Saved. " + result.item().name() + " is in "
                        + String.join(" > ", result.item().locationPath()) + ".");
    }

    private List<ItemSearchResult> searchAll(UUID userId, List<String> keywords) {
        Map<UUID, ItemSearchResult> merged = new LinkedHashMap<>();
        for (String keyword : keywords.subList(0, Math.min(keywords.size(), MAX_SEARCH_KEYWORDS))) {
            for (ItemSearchResult result : searchService.search(userId, keyword, MAX_ANSWER_ITEMS)) {
                merged.putIfAbsent(result.id(), result);
                if (merged.size() >= MAX_ANSWER_ITEMS) {
                    break;
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * SEARCH bookkeeping. FAILED carries a NULL interpretation even though the fallback keyword may
     * still have answered the user; ANSWERED stores only the keywords the search ran with (not the
     * hits — this table is provenance, not analytics); NOT_UNDERSTOOD means no search ran at all.
     */
    private void recordSearch(UUID userId, String sanitized, AiMetadata ai, SearchInterpretation interpretation,
                              List<String> keywords, boolean usedFallback, RuntimeException providerFailure) {
        if (providerFailure != null) {
            recordQuietly(new AssistantMessageDraft(userId, AssistantMode.SEARCH, sanitized, ai, null, null),
                    AssistantMessageResult.failed(errorCodeOf(providerFailure)));
        } else if (keywords.isEmpty()) {
            List<String> raw = interpretation == null ? List.of() : interpretation.keywords();
            recordQuietly(new AssistantMessageDraft(userId, AssistantMode.SEARCH, sanitized, ai,
                    InterpretationSnapshots.forSearchNotUnderstood(raw), null), AssistantMessageResult.notUnderstood());
        } else {
            recordQuietly(new AssistantMessageDraft(userId, AssistantMode.SEARCH, sanitized, ai,
                    InterpretationSnapshots.forSearch(keywords, usedFallback), null), AssistantMessageResult.answered());
        }
    }

    /**
     * Bookkeeping must never break a working feature. The catch sits HERE, at the caller of the
     * transactional proxy, and NOT inside {@link AssistantMessageService#record}: a try/catch inside a
     * REQUIRES_NEW method does not undo the rollback-only marking, so the proxy would still throw
     * {@code UnexpectedRollbackException} at the boundary and the user's request would fail anyway.
     * Logged: the outcome and the exception class — never the sentence, never the interpretation,
     * never the user beyond what the correlation id already carries.
     */
    private void recordQuietly(AssistantMessageDraft draft, AssistantMessageResult result) {
        try {
            messages.record(draft, result);
        } catch (RuntimeException e) {
            log.warn("Could not record assistant message (outcome={}): {}",
                    result.outcome(), e.getClass().getSimpleName());
        }
    }

    /** Provider-agnostic: an {@link ApiException} contributes its code, anything else is UNEXPECTED_ERROR. */
    private static String errorCodeOf(RuntimeException e) {
        return e instanceof ApiException api ? api.code().name() : AssistantMessageResult.UNEXPECTED_ERROR;
    }

    private static AssistantMessageDraft rememberDraft(UUID userId, String sanitized, AiMetadata ai,
                                                       InterpretationSnapshot snapshot) {
        return new AssistantMessageDraft(userId, AssistantMode.REMEMBER, sanitized, ai, snapshot,
                snapshot == null ? null : snapshot.confidence());
    }

    /**
     * Three genuinely different situations, so three different messages — a single "pick one"
     * wording is misleading when the user actually named a space that simply does not exist yet
     * (e.g. "işdə"/"at work" with no Office space).
     */
    private static String confirmationHint(List<Space> spaces, String named) {
        if (spaces.isEmpty()) {
            return "You have no spaces yet. Create one first (for example \"Home\"), then try again.";
        }
        if (named != null) {
            return "You don't have a space for \"" + named + "\" yet. Create it first, or resend "
                    + "with the spaceId of one of your existing spaces.";
        }
        return "I couldn't tell which space you meant. Resend with the spaceId of one of these.";
    }

    private static List<SpaceOption> candidatesOf(List<Space> spaces) {
        return spaces.stream()
                .map(s -> new SpaceOption(s.getId(), s.getName()))
                .toList();
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
