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
import az.technest.whereis.item.ItemRepository;
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
 *
 * <p>There are three entry points, in descending order of how much is left to guess.
 *
 * <p>A pinned {@code locationId} (BR-7) settles the destination before the request starts, and the
 * decision that follows from that is the important one: <strong>no model is called at all and the
 * text is the item name, as typed</strong>. Once the place is chosen there is nothing to interpret,
 * and interpreting anyway is what produced the failure this path exists to avoid — a bare
 * "kabel 20A" is not a placement statement, so a prompt calibrated to score placement statements
 * correctly rejects it. Skipping the provider also means no cost, no latency and no location
 * resolution of any kind on the hot path of filing many things in a row.
 *
 * <p>An explicit {@code spaceId} (BR-2) settles only the space, and the provider still interprets
 * the sentence. With neither, the space is resolved from the model's answer and the chain is
 * resolved-or-created under it.
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
    private final ItemRepository itemRepository;
    private final AiProperties aiProperties;
    private final AssistantMessageService messages;

    public RememberResponse remember(UUID userId, String message, UUID chosenSpaceId, UUID pinnedLocationId) {
        String sanitized = sanitize(message, MAX_REMEMBER_LENGTH);
        requireOneTarget(chosenSpaceId, pinnedLocationId);
        if (pinnedLocationId != null) {
            // Before the provider, not after: nothing here needs interpreting.
            return placeVerbatim(userId, sanitized, pinnedLocationId);
        }
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
        // Read before the provider call and outside any transaction, exactly as `remember` reads
        // space names: no AI call may sit inside a transaction, and this method holds none.
        List<String> offeredItems = offeredItemNames(userId);
        AiMetadata ai = aiAssistant.metadata(AssistantMode.SEARCH);
        SearchInterpretation interpretation = null;
        AiAssistantException providerFailure = null;
        List<String> keywords;
        List<String> matches;
        try {
            interpretation = aiAssistant.interpretSearch(sanitized, offeredItems);
            keywords = validator.validateKeywords(interpretation);
            matches = validator.validateMatches(interpretation);
        } catch (AiAssistantException e) {
            // Search must degrade gracefully when the provider is down.
            providerFailure = e;
            keywords = List.of();
            matches = List.of();
        }
        boolean usedFallback = false;
        if (keywords.isEmpty()) {
            String fallback = Names.normalize(sanitized);
            keywords = fallback != null && fallback.length() >= 2 ? List.of(fallback) : List.of();
            usedFallback = !keywords.isEmpty();
        }
        // The names the model picked win outright when they resolve, because they answer a
        // question the keywords cannot: "the thing I drill holes with" shares no letters with
        // "Matkap", so the trigram search behind `searchAll` would return nothing for it.
        // Everything else falls through to the path this method has always taken — an inventory
        // over the cap, a provider that is down, a model that picked nothing, and a model that
        // picked a name the user does not actually own. That last case is why this is `isEmpty()`
        // on the RESOLVED rows rather than on `matches`: an invented name must not swallow the
        // search, it must simply fail to resolve.
        List<ItemSearchResult> items = matches.isEmpty()
                ? List.of() : searchService.findByNames(userId, matches, MAX_ANSWER_ITEMS);
        if (items.isEmpty()) {
            items = searchAll(userId, keywords);
        }
        recordSearch(userId, sanitized, ai, interpretation, keywords, matches, offeredItems.size(),
                usedFallback, providerFailure);
        return new AssistantSearchResponse(composeAnswer(items), items);
    }

    /**
     * The candidate list handed to the model, or empty when it must not be.
     *
     * <p>Empty for two different reasons that deliberately look the same from here: the feature is
     * switched off ({@code ai.max-item-names=0}), or this account has more active items than the
     * cap. The second is decision 2 of the design — <strong>above the cap the list is not sent at
     * all rather than truncated</strong>, because an item that is unfindable for having fallen off
     * the end of a list is a worse failure than a search that plainly works the older, lexical way.
     *
     * <p>The count is the same scoped query the plan limits use, so the gate costs one cheap
     * {@code COUNT} and the names are never loaded for an account that would not send them.
     */
    private List<String> offeredItemNames(UUID userId) {
        int cap = aiProperties.maxItemNames();
        if (cap <= 0) {
            return List.of();
        }
        return itemRepository.countByUserIdAndArchivedFalse(userId) > cap
                ? List.of() : itemRepository.findActiveNamesByUserId(userId);
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

    private static String savedMessage(ItemResponse item) {
        return "Saved. " + item.name() + " is in " + String.join(" > ", item.locationPath()) + ".";
    }

    /**
     * BR-7, the pinned path end to end. No provider is consulted, so there is no interpretation,
     * no confidence and no space resolution: the sanitized text is the item name and the chosen
     * location is the destination.
     *
     * <p>The text still has to pass the item-name rule, which is not model distrust — it is what
     * the column and the UI can hold. A rejection answers NOT_UNDERSTOOD rather than 400 because
     * the client already renders that status with the message and an "Add manually" fallback, and
     * because the user did nothing malformed; their text simply cannot be a name. The row is still
     * written: the sentence is the user's, and the outcome is the record of why nothing happened.
     *
     * <p>Provenance shape: {@link AiMetadata#NONE} and a NULL interpretation, which together are
     * the discriminator for this path — a NULL interpretation with a real provider means the
     * provider failed instead. What is lost by not calling the model is the comparison "what would
     * it have answered for a sentence whose right destination is known"; that is a deliberate
     * trade for a path that must be fast, free and literal.
     */
    private RememberResponse placeVerbatim(UUID userId, String itemName, UUID locationId) {
        AssistantMessageDraft draft = new AssistantMessageDraft(userId, AssistantMode.REMEMBER, itemName,
                AiMetadata.NONE, null, null);
        if (!validator.isUsableItemName(itemName)) {
            recordQuietly(draft, AssistantMessageResult.notUnderstood());
            return RememberResponse.notUnderstood(
                    "With a place already chosen, the text is saved as the item name exactly as typed. "
                            + "Keep it under 120 characters and use only letters, digits and . , ' & ( ) -");
        }
        PlacementExecutor.ExecutionResult result;
        try {
            result = executor.placeAt(userId, locationId, itemName, null, PLACEMENT_NOTE);
        } catch (RuntimeException e) {
            // No space link: the ownership check that would have revealed the space is what threw.
            recordQuietly(draft, AssistantMessageResult.failed(errorCodeOf(e)));
            throw e;
        }
        recordQuietly(draft, AssistantMessageResult.created(result.item().id(), result.spaceId()));
        return RememberResponse.created(result.item(), result.createdLocations(), savedMessage(result.item()));
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
                              List<String> keywords, List<String> matches, int offeredItemCount,
                              boolean usedFallback, RuntimeException providerFailure) {
        if (providerFailure != null) {
            recordQuietly(new AssistantMessageDraft(userId, AssistantMode.SEARCH, sanitized, ai, null, null),
                    AssistantMessageResult.failed(errorCodeOf(providerFailure)));
        } else if (keywords.isEmpty() && matches.isEmpty()) {
            List<String> raw = interpretation == null ? List.of() : interpretation.keywords();
            recordQuietly(new AssistantMessageDraft(userId, AssistantMode.SEARCH, sanitized, ai,
                    InterpretationSnapshots.forSearchNotUnderstood(raw), null), AssistantMessageResult.notUnderstood());
        } else {
            recordQuietly(new AssistantMessageDraft(userId, AssistantMode.SEARCH, sanitized, ai,
                    InterpretationSnapshots.forSearch(keywords, matches, offeredItemCount, usedFallback), null),
                    AssistantMessageResult.answered());
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
