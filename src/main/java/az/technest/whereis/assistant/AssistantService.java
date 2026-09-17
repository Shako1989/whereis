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
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.location.ChainSegment;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 *
 * <p>Entity resolution has three entry points, in descending order of how much is left to guess.
 * A pinned {@code locationId} (BR-7) settles the destination outright and skips both space
 * resolution and chain resolution, so no location can be created; an explicit {@code spaceId}
 * (BR-2) settles only the space; with neither, the space is resolved from the model's answer and
 * the chain is resolved-or-created under it. The provider is called identically on all three —
 * same message, same {@code knownSpaceNames} — deliberately, so that a pinned row records what the
 * model WOULD have answered and stays comparable with the rows where the model decided. That is
 * the only labelled evidence we have for whether the model's placement is improving.
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

    public RememberResponse remember(UUID userId, String message, UUID chosenSpaceId, UUID pinnedLocationId) {
        String sanitized = sanitize(message, MAX_REMEMBER_LENGTH);
        requireOneTarget(chosenSpaceId, pinnedLocationId);
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
        if (pinnedLocationId != null) {
            return placeAtPinned(pinnedLocationId, placement, draft);
        }
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
                savedMessage(result.item()));
    }

    /**
     * BR-7. Same after-commit discipline as {@link #place}, with two differences that both follow
     * from the destination being settled before the request started. The FAILED row carries no
     * space: on this path the space is only known once the location lookup inside the executor's
     * transaction has succeeded, and that lookup is one of the things that can throw (a foreign or
     * deleted id is a 404), so claiming a space link here would sometimes be a lie.
     *
     * <p>And the sentence's own idea of the place is reported, never obeyed. Letting it win would
     * reintroduce precisely the model trust a pin exists to remove; leaving it unmentioned would
     * make a stale pin invisible, which is the only new way this path can go wrong.
     */
    private RememberResponse placeAtPinned(UUID locationId, ValidatedPlacement placement,
                                           AssistantMessageDraft draft) {
        PlacementExecutor.ExecutionResult result;
        try {
            result = executor.placeAt(draft.userId(), locationId, placement, PLACEMENT_NOTE);
        } catch (RuntimeException e) {
            recordQuietly(draft, AssistantMessageResult.failed(errorCodeOf(e)));
            throw e;
        }
        recordQuietly(draft, AssistantMessageResult.created(result.item().id(), result.spaceId()));
        boolean ignored = namesSomewhereElse(placement, result.item().locationPath());
        String message = ignored
                ? savedMessage(result.item()) + " The place named in your message was ignored:"
                        + " you had already pinned this one."
                : savedMessage(result.item());
        return RememberResponse.created(result.item(), result.createdLocations(), message, ignored);
    }

    private static String savedMessage(ItemResponse item) {
        return "Saved. " + item.name() + " is in " + String.join(" > ", item.locationPath()) + ".";
    }

    /**
     * Whether the sentence pointed somewhere other than where the item was pinned.
     *
     * <p>{@code locationPath} opens with the SPACE name and continues with the locations
     * ({@code LocationTreeDao.PATHS_SQL} joins {@code spaces} for exactly that), so one membership
     * test over it covers both halves of the interpretation: the innermost chain segment — the part
     * that claims where inside the space the item went — and the space itself, which is the half
     * that went wrong in the incident this feature exists for ("Work" instead of the listed
     * "Xalqlar").
     *
     * <p>Only the innermost segment is checked, not every segment: a shortened chain naming just
     * the box ("karobkaya qoydum") agrees with a pin on that box, and calling it a disagreement
     * because it omitted the room would make the warning fire on the common case and stop being
     * read. A sentence that named no place at all ("termos", after a pin) is likewise not a
     * disagreement — it is the whole point of pinning.
     */
    private static boolean namesSomewhereElse(ValidatedPlacement placement, List<String> pinnedPath) {
        Set<String> pinned = pinnedPath.stream().map(Names::normalize).collect(Collectors.toSet());
        List<ChainSegment> segments = placement.segments();
        if (!segments.isEmpty() && !pinned.contains(Names.normalize(segments.getLast().name()))) {
            return true;
        }
        return placement.spaceName() != null && !pinned.contains(Names.normalize(placement.spaceName()));
    }

    /**
     * A location already implies its space, so the two ids together can only express agreement
     * (redundant) or a contradiction (unanswerable). Rejecting both is how the contradiction stops
     * being a silent precedence rule that callers have to know about.
     */
    private static void requireOneTarget(UUID chosenSpaceId, UUID pinnedLocationId) {
        if (chosenSpaceId != null && pinnedLocationId != null) {
            throw new BadRequestException(ErrorCode.VALIDATION_ERROR,
                    "Send either spaceId or locationId, not both: a location already names its space");
        }
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
