package az.technest.whereis.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import az.technest.whereis.assistant.dto.AssistantSearchResponse;
import az.technest.whereis.assistant.dto.RememberResponse;
import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.error.NotFoundException;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.search.SearchService;
import az.technest.whereis.search.dto.ItemSearchResult;
import az.technest.whereis.space.Space;
import az.technest.whereis.space.SpaceRepository;
import az.technest.whereis.space.SpaceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class AssistantServiceTest {

    private static final AiMetadata META = new AiMetadata("mock", "mock-rules", "mock-1");

    @Mock
    private AiAssistant aiAssistant;
    @Mock
    private SpaceRepository spaceRepository;
    @Mock
    private PlacementExecutor executor;
    @Mock
    private SearchService searchService;
    @Mock
    private AssistantMessageService messages;

    private AssistantService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AssistantService(aiAssistant, new InterpretationValidator(),
                spaceRepository, executor, searchService, messages);
        // Every flow asks for the provider's metadata before it calls it; most tests do not care.
        lenient().when(aiAssistant.metadata(any())).thenReturn(META);
    }

    private static PlacementInterpretation interpretation(String spaceName, double confidence) {
        return new PlacementInterpretation("Passport", null, spaceName,
                List.of(new LocationSegment("Bedroom", "ROOM"), new LocationSegment("Top Drawer", "DRAWER")),
                confidence);
    }

    private Space space(String name) {
        return Space.builder().id(UUID.randomUUID()).userId(userId).name(name)
                .normalizedName(name.toLowerCase()).type(SpaceType.HOME).build();
    }

    private static ItemResponse item(String name, List<String> path) {
        return new ItemResponse(UUID.randomUUID(), name, null, null, UUID.randomUUID(), path,
                null, null, false, Instant.now(), Instant.now());
    }

    /** The one row the service handed to the recorder: the draft it built and how it said it ended. */
    private record RecordedRow(AssistantMessageDraft draft, AssistantMessageResult result) {
    }

    private RecordedRow recordedRow(AssistantOutcome outcome) {
        ArgumentCaptor<AssistantMessageDraft> draft = ArgumentCaptor.forClass(AssistantMessageDraft.class);
        ArgumentCaptor<AssistantMessageResult> result = ArgumentCaptor.forClass(AssistantMessageResult.class);
        verify(messages).record(draft.capture(), result.capture());
        assertThat(result.getValue().outcome()).isEqualTo(outcome);
        return new RecordedRow(draft.getValue(), result.getValue());
    }

    private AssistantMessageDraft recordedDraft(AssistantOutcome outcome) {
        return recordedRow(outcome).draft();
    }

    @Test
    void lowConfidenceInterpretationNeverMutatesAnything() {
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation(null, 0.1));

        RememberResponse response = service.remember(userId, "gibberish", null, null);

        assertThat(response.status()).isEqualTo(RememberResponse.Status.NOT_UNDERSTOOD);
        verify(executor, never()).place(any(), any(), any(), any());
    }

    @Test
    void hallucinatedSpaceNameIsNotTrusted() {
        // The AI names a space the user does not have — nothing may be created.
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation("Moon Base", 0.95));
        when(spaceRepository.findByUserIdAndNormalizedName(userId, "moon base")).thenReturn(Optional.empty());
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId)).thenReturn(List.of(space("Home")));

        RememberResponse response = service.remember(userId, "I put my passport in the moon base drawer", null, null);

        assertThat(response.status()).isEqualTo(RememberResponse.Status.NEEDS_CONFIRMATION);
        assertThat(response.candidateSpaces()).hasSize(1);
        verify(executor, never()).place(any(), any(), any(), any());
    }

    @Test
    void multipleSpacesWithoutExplicitNameNeedConfirmation() {
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation(null, 0.9));
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId))
                .thenReturn(List.of(space("Home"), space("Office")));

        RememberResponse response = service.remember(userId, "I put my passport in the bedroom drawer", null, null);

        assertThat(response.status()).isEqualTo(RememberResponse.Status.NEEDS_CONFIRMATION);
        assertThat(response.candidateSpaces()).hasSize(2);
        verify(executor, never()).place(any(), any(), any(), any());
    }

    @Test
    void zeroSpacesNeedsConfirmationWithGuidance() {
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation(null, 0.9));
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId)).thenReturn(List.of());

        RememberResponse response = service.remember(userId, "I put my passport in the bedroom drawer", null, null);

        assertThat(response.status()).isEqualTo(RememberResponse.Status.NEEDS_CONFIRMATION);
        assertThat(response.candidateSpaces()).isEmpty();
        verify(executor, never()).place(any(), any(), any(), any());
    }

    @Test
    void singleSpaceResolvesAutomaticallyAndPlaces() {
        Space home = space("Home");
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation(null, 0.9));
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId)).thenReturn(List.of(home));
        ItemResponse item = item("Passport", List.of("Home", "Bedroom", "Top Drawer"));
        when(executor.place(eq(userId), eq(home.getId()), any(), eq(AssistantService.PLACEMENT_NOTE)))
                .thenReturn(new PlacementExecutor.ExecutionResult(item, List.of("Bedroom", "Top Drawer"),
                        home.getId()));

        RememberResponse response = service.remember(userId, "I put my passport in the bedroom top drawer", null, null);

        assertThat(response.status()).isEqualTo(RememberResponse.Status.CREATED);
        assertThat(response.item().name()).isEqualTo("Passport");
        assertThat(response.createdLocations()).containsExactly("Bedroom", "Top Drawer");
    }

    @Test
    void explicitSpaceNameResolvesAgainstTheDatabase() {
        Space home = space("Home");
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation("Home", 0.9));
        when(spaceRepository.findByUserIdAndNormalizedName(userId, "home")).thenReturn(Optional.of(home));
        when(executor.place(eq(userId), eq(home.getId()), any(), eq(AssistantService.PLACEMENT_NOTE)))
                .thenReturn(new PlacementExecutor.ExecutionResult(
                        item("Passport", List.of("Home", "Bedroom", "Top Drawer")), List.of(), home.getId()));

        assertThat(service.remember(userId, "I put my passport in the bedroom drawer at home", null, null).status())
                .isEqualTo(RememberResponse.Status.CREATED);
    }

    @Test
    void searchFallsBackToRawQueryWhenAiIsDown() {
        when(aiAssistant.interpretSearch(anyString())).thenThrow(new AiAssistantException("down"));
        when(searchService.search(eq(userId), eq("passport"), anyInt())).thenReturn(List.of());

        AssistantSearchResponse response = service.search(userId, "Passport");

        assertThat(response.answer()).contains("couldn't find");
        verify(searchService).search(eq(userId), eq("passport"), anyInt());
    }

    @Test
    void searchAnswerIsComposedFromRetrievedRecordsOnly() {
        when(aiAssistant.interpretSearch(anyString()))
                .thenReturn(new SearchInterpretation(List.of("passport")));
        ItemSearchResult result = new ItemSearchResult(UUID.randomUUID(), "Passport",
                List.of("Home", "Bedroom", "Wardrobe", "Top Drawer"), null, Instant.now());
        when(searchService.search(eq(userId), eq("passport"), anyInt())).thenReturn(List.of(result));

        AssistantSearchResponse response = service.search(userId, "Where is my passport?");

        assertThat(response.answer())
                .isEqualTo("Your Passport is in Home > Bedroom > Wardrobe > Top Drawer.");
        assertThat(response.items()).hasSize(1);
    }

    // ------------------------------------------------- BR-2: an explicitly chosen space

    @Test
    void anExplicitSpaceIdSettlesTheSpaceEvenWhenTwoWouldBeAmbiguous() {
        // The answer to a previous NEEDS_CONFIRMATION. Two spaces exist and the message names
        // none, so without the id this would ask again instead of writing.
        Space chosen = space("Home");
        when(aiAssistant.interpretPlacement(anyString(), anyList()))
                .thenReturn(interpretation(null, 0.9));
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId))
                .thenReturn(List.of(space("Garden"), chosen));
        when(spaceRepository.findByIdAndUserId(chosen.getId(), userId)).thenReturn(Optional.of(chosen));
        when(executor.place(eq(userId), eq(chosen.getId()), any(), eq(AssistantService.PLACEMENT_NOTE)))
                .thenReturn(new PlacementExecutor.ExecutionResult(
                        item("Passport", List.of("Home", "Bedroom", "Top Drawer")),
                        List.of("Bedroom", "Top Drawer"), chosen.getId()));

        RememberResponse response =
                service.remember(userId, "I put my passport in the bedroom drawer", chosen.getId(), null);

        assertThat(response.status()).isEqualTo(RememberResponse.Status.CREATED);
        verify(executor).place(eq(userId), eq(chosen.getId()), any(),
                eq(AssistantService.PLACEMENT_NOTE));
    }

    @Test
    void aSpaceIdThatIsNotThisUsersIsANotFound() {
        // Ownership misses are 404 everywhere in this codebase, never 403: an id must not be
        // probeable for existence.
        UUID someoneElses = UUID.randomUUID();
        when(aiAssistant.interpretPlacement(anyString(), anyList()))
                .thenReturn(interpretation(null, 0.9));
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId)).thenReturn(List.of(space("Home")));
        when(spaceRepository.findByIdAndUserId(someoneElses, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.remember(userId, "I put my passport in the bedroom drawer", someoneElses, null))
                .isInstanceOf(NotFoundException.class);
        verify(executor, never()).place(any(), any(), any(), any());
    }

    @Test
    void theUsersOwnSpaceNamesAreHandedToTheProvider() {
        // Names only, never ids, and never anything the provider could act on directly.
        when(aiAssistant.interpretPlacement(anyString(), anyList()))
                .thenReturn(interpretation(null, 0.1));
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId))
                .thenReturn(List.of(space("Garden"), space("Home")));

        service.remember(userId, "gibberish", null, null);

        verify(aiAssistant).interpretPlacement("gibberish", List.of("Garden", "Home"));
    }

    // ------------------------------------------------- V8: one assistant_messages row per request

    @Test
    void notUnderstoodRecordsTheRawSnapshotAndNeverCallsTheExecutor() {
        when(aiAssistant.interpretPlacement(anyString(), anyList()))
                .thenReturn(new PlacementInterpretation("Silicon vuran xalq", null, "Xalqlar",
                        List.of(new LocationSegment("Kladovka", "SHELF-ISH")), 0.1));

        service.remember(userId, "gibberish", null, null);

        AssistantMessageDraft draft = recordedDraft(AssistantOutcome.NOT_UNDERSTOOD);
        assertThat(draft.userId()).isEqualTo(userId);
        assertThat(draft.mode()).isEqualTo(AssistantMode.REMEMBER);
        assertThat(draft.message()).isEqualTo("gibberish");
        assertThat(draft.ai()).isEqualTo(META);
        // The RAW output, so the model's actual answer — including an unknown type — is inspectable.
        assertThat(draft.interpretation().validated()).isFalse();
        assertThat(draft.interpretation().itemName()).isEqualTo("Silicon vuran xalq");
        assertThat(draft.interpretation().spaceName()).isEqualTo("Xalqlar");
        assertThat(draft.interpretation().locations()).singleElement()
                .satisfies(segment -> assertThat(segment.type()).isEqualTo("SHELF-ISH"));
        assertThat(draft.confidence()).isEqualByComparingTo("0.100");
        verify(executor, never()).place(any(), any(), any(), any());
        verify(aiAssistant).metadata(AssistantMode.REMEMBER);
    }

    @Test
    void needsConfirmationRecordsExactlyOneRowWithTheValidatedSnapshot() {
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation(null, 0.9));
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId))
                .thenReturn(List.of(space("Home"), space("Office")));

        service.remember(userId, "I put my passport in the bedroom drawer", null, null);

        AssistantMessageDraft draft = recordedDraft(AssistantOutcome.NEEDS_CONFIRMATION);
        assertThat(draft.interpretation().validated()).isTrue();
        assertThat(draft.interpretation().itemName()).isEqualTo("Passport");
        assertThat(draft.interpretation().spaceName()).isNull();
        assertThat(draft.interpretation().locations()).extracting(InterpretationSnapshot.Segment::type)
                .containsExactly("ROOM", "DRAWER");
        assertThat(draft.confidence()).isEqualByComparingTo("0.900");
        verify(messages).record(any(), any());
        verify(executor, never()).place(any(), any(), any(), any());
    }

    @Test
    void aThrowingProviderRecordsFailedWithNoModelOutputAndRethrows() {
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId)).thenReturn(List.of(space("Home")));
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenThrow(new AiAssistantException("down"));

        assertThatThrownBy(() -> service.remember(userId, "I put my passport in the bedroom drawer", null, null))
                .isInstanceOf(AiAssistantException.class);

        RecordedRow row = recordedRow(AssistantOutcome.FAILED);
        assertThat(row.result().itemId()).isNull();
        // Nothing was resolved before the provider threw, so there is nothing to link.
        assertThat(row.result().spaceId()).isNull();
        assertThat(row.result().errorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE.name());
        AssistantMessageDraft draft = row.draft();
        assertThat(draft.mode()).isEqualTo(AssistantMode.REMEMBER);
        assertThat(draft.message()).isEqualTo("I put my passport in the bedroom drawer");
        assertThat(draft.confidence()).isNull();
        // There is no answer to snapshot: the row keeps only the request context (what the model was
        // offered), so no field of it can be mistaken for something the provider said.
        InterpretationSnapshot snapshot = draft.interpretation();
        assertThat(snapshot.validated()).isNull();
        assertThat(snapshot.itemName()).isNull();
        assertThat(snapshot.spaceName()).isNull();
        assertThat(snapshot.locations()).isNull();
        assertThat(snapshot.confidence()).isNull();
        assertThat(snapshot.offeredSpaces()).containsExactly("Home");
        verify(executor, never()).place(any(), any(), any(), any());
    }

    @Test
    void aThrowingProviderWithNothingToOfferRecordsFailedWithANullInterpretation() {
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId)).thenReturn(List.of());
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenThrow(new AiAssistantException("down"));

        assertThatThrownBy(() -> service.remember(userId, "I put my passport in the bedroom drawer", null, null))
                .isInstanceOf(AiAssistantException.class);

        AssistantMessageDraft draft = recordedDraft(AssistantOutcome.FAILED);
        assertThat(draft.interpretation()).isNull();
        assertThat(draft.confidence()).isNull();
    }

    @Test
    void theCreatedRowIsRecordedAfterTheExecutorReturnedAndCarriesTheItemAndSpace() {
        Space home = space("Home");
        ItemResponse created = item("Passport", List.of("Home", "Bedroom", "Top Drawer"));
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation("Home", 0.93));
        when(spaceRepository.findByUserIdAndNormalizedName(userId, "home")).thenReturn(Optional.of(home));
        when(executor.place(eq(userId), eq(home.getId()), any(), eq(AssistantService.PLACEMENT_NOTE)))
                .thenReturn(new PlacementExecutor.ExecutionResult(created, List.of(), home.getId()));

        service.remember(userId, "I put my passport in the bedroom drawer at home", null, null);

        // Order is the contract: the executor's transactional proxy has committed by the time it
        // returns, so recording afterwards IS the after-commit point — and only then does an item id
        // exist to link. The executor knows nothing about the row.
        InOrder order = inOrder(executor, messages);
        order.verify(executor).place(eq(userId), eq(home.getId()), any(), eq(AssistantService.PLACEMENT_NOTE));
        order.verify(messages).record(any(), any());

        RecordedRow row = recordedRow(AssistantOutcome.CREATED);
        assertThat(row.result().itemId()).isEqualTo(created.id());
        assertThat(row.result().spaceId()).isEqualTo(home.getId());
        assertThat(row.result().errorCode()).isNull();
        AssistantMessageDraft draft = row.draft();
        assertThat(draft.userId()).isEqualTo(userId);
        assertThat(draft.mode()).isEqualTo(AssistantMode.REMEMBER);
        assertThat(draft.message()).isEqualTo("I put my passport in the bedroom drawer at home");
        assertThat(draft.ai().provider()).isEqualTo("mock");
        assertThat(draft.ai().model()).isEqualTo("mock-rules");
        assertThat(draft.ai().promptVersion()).isEqualTo("mock-1");
        assertThat(draft.confidence()).isEqualByComparingTo("0.930");
        assertThat(draft.interpretation().validated()).isTrue();
        assertThat(draft.interpretation().spaceName()).isEqualTo("Home");
    }

    @Test
    void anExecutorFailureRecordsFailedWithTheResolvedSpaceAndRethrows() {
        // The placement transaction rolled back, so there is no item to link — but the space was
        // resolved before it ran, and that is the half of the diagnosis worth keeping.
        Space home = space("Home");
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation("Home", 0.93));
        when(spaceRepository.findByUserIdAndNormalizedName(userId, "home")).thenReturn(Optional.of(home));
        when(executor.place(eq(userId), eq(home.getId()), any(), eq(AssistantService.PLACEMENT_NOTE)))
                .thenThrow(new NotFoundException(ErrorCode.LOCATION_NOT_FOUND, "Location not found"));

        assertThatThrownBy(() ->
                service.remember(userId, "I put my passport in the bedroom drawer at home", null, null))
                .isInstanceOf(NotFoundException.class);

        RecordedRow row = recordedRow(AssistantOutcome.FAILED);
        assertThat(row.result().itemId()).isNull();
        assertThat(row.result().spaceId()).isEqualTo(home.getId());
        assertThat(row.result().errorCode()).isEqualTo(ErrorCode.LOCATION_NOT_FOUND.name());
        assertThat(row.draft().interpretation().validated()).isTrue();
        assertThat(row.draft().confidence()).isEqualByComparingTo("0.930");
    }

    @Test
    void aFailingRecorderDoesNotBreakTheUsersResponse() {
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation(null, 0.9));
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId))
                .thenReturn(List.of(space("Home"), space("Office")));
        doThrow(new IllegalStateException("database is away")).when(messages).record(any(), any());

        RememberResponse response = service.remember(userId, "I put my passport in the bedroom drawer", null, null);

        assertThat(response.status()).isEqualTo(RememberResponse.Status.NEEDS_CONFIRMATION);
        assertThat(response.candidateSpaces()).hasSize(2);
    }

    @Test
    void aMessageThatFailsSanitizeRecordsNothingAndNeverReachesTheProvider() {
        assertThatThrownBy(() -> service.remember(userId, "   ", null, null))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(messages, executor);
        verify(aiAssistant, never()).interpretPlacement(any(), any());
    }

    @Test
    void searchThatRanRecordsAnsweredWithTheKeywordsUsed() {
        when(aiAssistant.interpretSearch(anyString()))
                .thenReturn(new SearchInterpretation(List.of("passport", "Passport", "travel document")));
        when(searchService.search(eq(userId), anyString(), anyInt())).thenReturn(List.of());

        service.search(userId, "Where is my passport?");

        AssistantMessageDraft draft = recordedDraft(AssistantOutcome.ANSWERED);
        assertThat(draft.mode()).isEqualTo(AssistantMode.SEARCH);
        assertThat(draft.message()).isEqualTo("Where is my passport?");
        assertThat(draft.interpretation().validated()).isTrue();
        assertThat(draft.interpretation().keywords()).containsExactly("passport", "travel document");
        assertThat(draft.interpretation().usedFallback()).isFalse();
        assertThat(draft.interpretation().itemName()).isNull();
        assertThat(draft.confidence()).isNull();
        verify(aiAssistant).metadata(AssistantMode.SEARCH);
    }

    @Test
    void searchThatFellBackToTheNormalizedSentenceSaysSo() {
        when(aiAssistant.interpretSearch(anyString())).thenReturn(new SearchInterpretation(List.of()));
        when(searchService.search(eq(userId), eq("passport"), anyInt())).thenReturn(List.of());

        service.search(userId, "Passport");

        AssistantMessageDraft draft = recordedDraft(AssistantOutcome.ANSWERED);
        assertThat(draft.interpretation().keywords()).containsExactly("passport");
        assertThat(draft.interpretation().usedFallback()).isTrue();
    }

    @Test
    void searchWithNothingUsableAndNoFallbackRecordsNotUnderstoodAndRunsNoSearch() {
        // "!" is dropped by the validator and "a" is too short for the normalize fallback.
        when(aiAssistant.interpretSearch(anyString())).thenReturn(new SearchInterpretation(List.of("!")));

        AssistantSearchResponse response = service.search(userId, "a");

        AssistantMessageDraft draft = recordedDraft(AssistantOutcome.NOT_UNDERSTOOD);
        assertThat(draft.interpretation().validated()).isFalse();
        assertThat(draft.interpretation().keywords()).isEmpty();
        assertThat(draft.interpretation().rawKeywords()).containsExactly("!");
        assertThat(response.items()).isEmpty();
        verifyNoInteractions(searchService);
    }

    @Test
    void searchProviderFailureRecordsFailedWithANullInterpretationAndStillAnswers() {
        when(aiAssistant.interpretSearch(anyString())).thenThrow(new AiAssistantException("down"));
        when(searchService.search(eq(userId), eq("passport"), anyInt())).thenReturn(List.of());

        AssistantSearchResponse response = service.search(userId, "Passport");

        AssistantMessageDraft draft = recordedDraft(AssistantOutcome.FAILED);
        assertThat(draft.mode()).isEqualTo(AssistantMode.SEARCH);
        assertThat(draft.interpretation()).isNull();
        // Degraded but answered: the FAILED row does not mean the user got nothing.
        assertThat(response.answer()).contains("couldn't find");
    }

    @Test
    void aRecorderFailureOnTheCreatedPathStillLeavesTheUserTheirItem() {
        // The swallow matters most here: the item is already committed, so an escaping recorder
        // exception would answer 500 for a placement that actually succeeded.
        Space home = space("Home");
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation("Home", 0.93));
        when(spaceRepository.findByUserIdAndNormalizedName(userId, "home")).thenReturn(Optional.of(home));
        when(executor.place(eq(userId), eq(home.getId()), any(), eq(AssistantService.PLACEMENT_NOTE)))
                .thenReturn(new PlacementExecutor.ExecutionResult(
                        item("Passport", List.of("Home", "Bedroom", "Top Drawer")), List.of(), home.getId()));
        doThrow(new IllegalStateException("insert failed")).when(messages).record(any(), any());

        RememberResponse response = service.remember(userId, "I put my passport in the bedroom drawer", null, null);

        assertThat(response.status()).isEqualTo(RememberResponse.Status.CREATED);
        assertThat(response.item().name()).isEqualTo("Passport");
        verify(messages).record(any(), any());
    }

    @Test
    void aForeignSpaceIdIsAClientErrorAndRecordsNothing() {
        // One of the two paths AssistantService's javadoc says record nothing: a 404 is a client
        // error, not an interpretation, and no outcome value describes it.
        UUID foreign = UUID.randomUUID();
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation(null, 0.93));
        when(spaceRepository.findByIdAndUserId(foreign, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remember(userId, "I put my passport in the drawer", foreign, null))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(messages);
        verify(executor, never()).place(any(), any(), any(), any());
    }

    @Test
    void theRecorderIsDeclaredRequiresNewSoACallerTransactionCannotSwallowTheRow() {
        // Pins the DECLARATION, not the runtime: AssistantService has no @Transactional today, so
        // the propagation is currently moot. It stops mattering the moment someone wraps a caller
        // in a transaction, and CLAUDE.md makes REQUIRES_NEW non-negotiable for exactly that case.
        // A runtime proof needs fault injection across a real transaction boundary (see the gaps
        // listed in the handover) — this guards the contract for the price of reflection.
        Transactional annotation = org.springframework.util.ReflectionUtils
                .findMethod(AssistantMessageService.class, "record",
                        AssistantMessageDraft.class, AssistantMessageResult.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    // ---- BR-7: a pinned locationId ------------------------------------------------------------

    /**
     * The whole point of the pin: {@code resolveOrCreateChain} — the only auto-creation path in the
     * system — is unreachable, so a wrong or duplicate location is not unlikely on this path, it is
     * impossible. Pinned by asserting the space lookup never happens either: the space cannot be
     * guessed wrong if it is never guessed.
     */
    @Test
    void aPinnedLocationSettlesTheDestinationWithoutResolvingASpaceOrAChain() {
        UUID pinned = UUID.randomUUID();
        UUID space = UUID.randomUUID();
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation("Home", 0.93));
        when(executor.placeAt(eq(userId), eq(pinned), any(), eq(AssistantService.PLACEMENT_NOTE)))
                .thenReturn(new PlacementExecutor.ExecutionResult(
                        item("Passport", List.of("Ashagi kladovka", "Karobka")), List.of(), space));

        RememberResponse response =
                service.remember(userId, "I put my passport in the bedroom top drawer", null, pinned);

        assertThat(response.status()).isEqualTo(RememberResponse.Status.CREATED);
        assertThat(response.createdLocations()).isEmpty();
        verify(executor, never()).place(any(), any(), any(), any());
        verify(spaceRepository, never()).findByUserIdAndNormalizedName(any(), any());
        verify(spaceRepository, never()).findByIdAndUserId(any(), any());
    }

    @Test
    void aPinnedLocationReportsThatTheSentencesOwnPlaceWasIgnored() {
        UUID pinned = UUID.randomUUID();
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation(null, 0.93));
        when(executor.placeAt(eq(userId), eq(pinned), any(), eq(AssistantService.PLACEMENT_NOTE)))
                .thenReturn(new PlacementExecutor.ExecutionResult(
                        item("Passport", List.of("Ashagi kladovka", "Karobka")), List.of(), UUID.randomUUID()));

        RememberResponse response =
                service.remember(userId, "I put my passport in the bedroom top drawer", null, pinned);

        // Reported, not obeyed — a stale pin has to be visible without the sentence being able to
        // move the item.
        assertThat(response.messagePlaceIgnored()).isTrue();
        assertThat(response.message()).contains("Ashagi kladovka > Karobka").contains("ignored");
        assertThat(response.item().locationPath()).containsExactly("Ashagi kladovka", "Karobka");
    }

    @Test
    void aPinnedLocationTheSentenceAlsoNamesIsNotReportedAsAnOverride() {
        UUID pinned = UUID.randomUUID();
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation(null, 0.93));
        when(executor.placeAt(eq(userId), eq(pinned), any(), eq(AssistantService.PLACEMENT_NOTE)))
                .thenReturn(new PlacementExecutor.ExecutionResult(
                        // The model's innermost segment is "Top Drawer" — the pinned leaf itself.
                        item("Passport", List.of("Bedroom", "Top Drawer")), List.of(), UUID.randomUUID()));

        RememberResponse response =
                service.remember(userId, "I put my passport in the bedroom top drawer", null, pinned);

        assertThat(response.messagePlaceIgnored()).isFalse();
        assertThat(response.message()).doesNotContain("ignored");
    }

    /**
     * The pinned path calls the provider with the same inputs as every other path on purpose: the
     * row it leaves is the only labelled evidence of what the model WOULD have answered for a
     * sentence whose right answer is known. Losing that would make the placement quality
     * unmeasurable exactly where it is cheapest to measure.
     */
    @Test
    void aPinnedRememberStillRecordsWhatTheModelAnsweredAndWhatItWasOffered() {
        UUID pinned = UUID.randomUUID();
        UUID space = UUID.randomUUID();
        ItemResponse created = item("Passport", List.of("Ashagi kladovka", "Karobka"));
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId))
                .thenReturn(List.of(space("Xalqlar"), space("Work")));
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation("Work", 0.93));
        when(executor.placeAt(eq(userId), eq(pinned), any(), eq(AssistantService.PLACEMENT_NOTE)))
                .thenReturn(new PlacementExecutor.ExecutionResult(created, List.of(), space));

        service.remember(userId, "I put my passport in the bedroom top drawer", null, pinned);

        verify(aiAssistant).interpretPlacement("I put my passport in the bedroom top drawer",
                List.of("Xalqlar", "Work"));
        RecordedRow row = recordedRow(AssistantOutcome.CREATED);
        assertThat(row.result().itemId()).isEqualTo(created.id());
        // The space comes from the executor: on this path only the location lookup knows it.
        assertThat(row.result().spaceId()).isEqualTo(space);
        assertThat(row.draft().interpretation().spaceName()).isEqualTo("Work");
        assertThat(row.draft().interpretation().offeredSpaces()).containsExactly("Xalqlar", "Work");
    }

    @Test
    void aPinnedLocationThatIsNotThisUsersRecordsFailedWithNoSpaceAndRethrows() {
        UUID pinned = UUID.randomUUID();
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation(null, 0.93));
        when(executor.placeAt(eq(userId), eq(pinned), any(), eq(AssistantService.PLACEMENT_NOTE)))
                .thenThrow(new NotFoundException(ErrorCode.LOCATION_NOT_FOUND, "Location not found"));

        assertThatThrownBy(() ->
                service.remember(userId, "I put my passport in the bedroom top drawer", null, pinned))
                .isInstanceOf(NotFoundException.class);

        AssistantMessageResult result = recordedRow(AssistantOutcome.FAILED).result();
        assertThat(result.errorCode()).isEqualTo(ErrorCode.LOCATION_NOT_FOUND.name());
        // No space link: the ownership check that would have revealed the space is what threw.
        assertThat(result.spaceId()).isNull();
    }

    @Test
    void aPinnedRememberTheValidatorRejectsNeverReachesTheExecutor() {
        UUID pinned = UUID.randomUUID();
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation(null, 0.2));

        RememberResponse response = service.remember(userId, "gibberish", null, pinned);

        assertThat(response.status()).isEqualTo(RememberResponse.Status.NOT_UNDERSTOOD);
        verify(executor, never()).placeAt(any(), any(), any(), any());
        verify(executor, never()).place(any(), any(), any(), any());
    }

    /**
     * A location already names its space, so the pair can only be redundant or contradictory.
     * Rejecting it keeps precedence out of the contract — and the rejection happens before the
     * provider is paid, which is the other reason it is a 400 rather than a documented winner.
     */
    @Test
    void sendingBothASpaceIdAndALocationIdIsARejectedContradiction() {
        assertThatThrownBy(() -> service.remember(userId, "I put my passport in the drawer",
                UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not both");

        verifyNoInteractions(aiAssistant, spaceRepository, executor, messages);
    }

    /**
     * The incident this feature answers: the sentence's innermost place was right and the SPACE was
     * wrong. {@code locationPath} carries the space as its first element, so the same membership
     * test catches it — and the pin still wins, which is the only reason the item is in the right
     * place at all.
     */
    @Test
    void aPinnedLocationReportsASentenceThatNamedTheRightBoxInTheWrongSpace() {
        UUID pinned = UUID.randomUUID();
        when(aiAssistant.interpretPlacement(anyString(), anyList()))
                .thenReturn(new PlacementInterpretation("Passport", null, "Work",
                        List.of(new LocationSegment("Karobka", "BOX")), 0.93));
        when(executor.placeAt(eq(userId), eq(pinned), any(), eq(AssistantService.PLACEMENT_NOTE)))
                .thenReturn(new PlacementExecutor.ExecutionResult(
                        item("Passport", List.of("Xalqlar", "Ashagi kladovka", "Karobka")),
                        List.of(), UUID.randomUUID()));

        RememberResponse response =
                service.remember(userId, "I put my passport in the karobka at work", null, pinned);

        assertThat(response.messagePlaceIgnored()).isTrue();
        assertThat(response.item().locationPath()).startsWith("Xalqlar");
    }
}
