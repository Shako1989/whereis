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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import az.technest.whereis.assistant.dto.AssistantSearchResponse;
import az.technest.whereis.assistant.dto.RememberResponse;
import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.error.NotFoundException;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.PlanLimitReachedException;
import az.technest.whereis.item.ItemRepository;
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

    @Mock
    private ItemRepository itemRepository;

    /**
     * A cap well above anything these tests register, so the item list is offered by default and
     * the gate is exercised explicitly by the one test that lowers it.
     */
    private static final AiProperties AI_PROPERTIES =
            new AiProperties("mock", null, null, null, 0.0, null, 0, 1000);

    private AssistantService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AssistantService(aiAssistant, new InterpretationValidator(),
                spaceRepository, executor, searchService, itemRepository, AI_PROPERTIES, messages);
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

    // ---------------------------------------------------------------------------------------
    // One word the database already knows: no model call at all.
    // ---------------------------------------------------------------------------------------

    @Test
    void aOneWordQueryTheDatabaseAnswersNeverReachesTheModel() {
        // Measured on real traffic: three of five one-word searches were answered here, two of
        // them better than the model managed. The saved call is the point, but the stronger
        // argument is that "kabel" finding "Qalin kabeller" needs no understanding at all.
        ItemSearchResult found = new ItemSearchResult(UUID.randomUUID(), "Qalin kabeller",
                List.of("Ev", "Anbar"), null, Instant.now());
        when(searchService.search(userId, "kabel", 10)).thenReturn(List.of(found));

        AssistantSearchResponse response = service.search(userId, "Kabel");

        assertThat(response.items()).containsExactly(found);
        verifyNoInteractions(aiAssistant);
        // And nothing was loaded to offer it, either: no item name left the server.
        verifyNoInteractions(itemRepository);
    }

    @Test
    void aOneWordQueryTheDatabaseCannotAnswerStillGoesToTheModel() {
        // "qutu" against an item named "RC controller Box" — no shared letters, so only a model
        // bridges them. Returning early on an EMPTY result would have broken exactly this.
        ItemSearchResult box = new ItemSearchResult(UUID.randomUUID(), "RC controller Box",
                List.of("Ev"), null, Instant.now());
        when(searchService.search(userId, "qutu", 10)).thenReturn(List.of());
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(1L);
        when(itemRepository.findActiveNamesByUserId(userId)).thenReturn(List.of("RC controller Box"));
        when(aiAssistant.interpretSearch(anyString(), anyList()))
                .thenReturn(new SearchInterpretation(List.of("qutu"), List.of("RC controller Box")));
        when(searchService.findByNames(eq(userId), eq(List.of("rc controller box")), anyInt()))
                .thenReturn(List.of(box));

        assertThat(service.search(userId, "qutu").items()).containsExactly(box);
        verify(aiAssistant).interpretSearch("qutu", List.of("RC controller Box"));
    }

    @Test
    void aSentenceIsNeverShortCircuitedEvenIfOneOfItsWordsWouldMatch() {
        // The whole sentence is a bad trigram term — the question words pollute the comparison —
        // so the guard is one word, not "try the database first".
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(1L);
        when(itemRepository.findActiveNamesByUserId(userId)).thenReturn(List.of("Kabel"));
        when(aiAssistant.interpretSearch(anyString(), anyList()))
                .thenReturn(new SearchInterpretation(List.of("kabel"), List.of()));
        when(searchService.search(eq(userId), eq("kabel"), anyInt())).thenReturn(List.of());

        service.search(userId, "kabel haradadir");

        verify(aiAssistant).interpretSearch("kabel haradadir", List.of("Kabel"));
    }

    @Test
    void aMissedOneWordQueryIsNotAskedOfTheDatabaseTwice() {
        // The short-circuit and the keyword fallback would otherwise run the identical query, on
        // the one path that is already paying for a model call.
        when(searchService.search(userId, "pasport", 10)).thenReturn(List.of());
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(1L);
        when(itemRepository.findActiveNamesByUserId(userId)).thenReturn(List.of("Cekic"));
        when(aiAssistant.interpretSearch(anyString(), anyList()))
                .thenReturn(new SearchInterpretation(List.of("pasport"), List.of()));

        assertThat(service.search(userId, "pasport").items()).isEmpty();

        verify(searchService, times(1)).search(userId, "pasport", 10);
    }

    @Test
    void aRedundantKeywordIsStillRecordedEvenThoughItWasNotSearchedAgain() {
        // The row says what the search RAN WITH. Dropping a keyword from it for being redundant
        // would make a later "why did this answer nothing?" unanswerable.
        when(searchService.search(userId, "pasport", 10)).thenReturn(List.of());
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(1L);
        when(itemRepository.findActiveNamesByUserId(userId)).thenReturn(List.of("Cekic"));
        when(aiAssistant.interpretSearch(anyString(), anyList()))
                .thenReturn(new SearchInterpretation(List.of("pasport"), List.of()));

        service.search(userId, "pasport");

        InterpretationSnapshot snapshot = recordedRow(AssistantOutcome.ANSWERED).draft().interpretation();
        assertThat(snapshot.keywords()).containsExactly("pasport");
        // The model WAS asked here, so this is not the saved-call row.
        assertThat(snapshot.withoutAi()).isNull();
    }

    @Test
    void theSavedCallIsRecordedAsHavingAskedNobody() {
        ItemSearchResult found = new ItemSearchResult(UUID.randomUUID(), "Salfetka",
                List.of("Ev"), null, Instant.now());
        when(searchService.search(userId, "salfetla", 10)).thenReturn(List.of(found));

        service.search(userId, "salfetla");

        RecordedRow row = recordedRow(AssistantOutcome.ANSWERED);
        // The row must not name a provider that was never asked.
        assertThat(row.draft().ai()).isEqualTo(AiMetadata.NONE);
        InterpretationSnapshot snapshot = row.draft().interpretation();
        assertThat(snapshot.withoutAi()).isTrue();
        // Not a fallback: nothing fell back, because nothing was asked.
        assertThat(snapshot.usedFallback()).isFalse();
        assertThat(snapshot.offeredItemCount()).isNull();
        assertThat(snapshot.keywords()).containsExactly("salfetla");
    }

    // ---------------------------------------------------------------------------------------
    // Finding an item by describing it: the caller's own item names go to the model, and what it
    // picks is resolved against the caller's own rows.
    // ---------------------------------------------------------------------------------------

    @Test
    void theUsersOwnItemNamesAreHandedToTheProvider() {
        // Names only, never ids — the same contract the space names travel under.
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(2L);
        when(itemRepository.findActiveNamesByUserId(userId)).thenReturn(List.of("Matkap", "Pasport"));
        when(aiAssistant.interpretSearch(anyString(), anyList()))
                .thenReturn(new SearchInterpretation(List.of("alet"), List.of()));
        when(searchService.search(eq(userId), anyString(), anyInt())).thenReturn(List.of());

        service.search(userId, "divarda desik acan alet");

        verify(aiAssistant).interpretSearch("divarda desik acan alet", List.of("Matkap", "Pasport"));
    }

    @Test
    void aPickedNameIsResolvedAgainstTheUsersOwnRowsAndTheKeywordSearchIsNotRun() {
        // This is the whole feature: "divarda deşik açan alət" shares no letters with "Matkap",
        // so the trigram search behind searchAll can never find it. The model selects; the
        // database retrieves.
        ItemSearchResult matkap = new ItemSearchResult(UUID.randomUUID(), "Matkap",
                List.of("Ev", "Anbar"), null, Instant.now());
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(1L);
        when(itemRepository.findActiveNamesByUserId(userId)).thenReturn(List.of("Matkap"));
        when(aiAssistant.interpretSearch(anyString(), anyList()))
                .thenReturn(new SearchInterpretation(List.of("alet"), List.of("Matkap")));
        when(searchService.findByNames(eq(userId), eq(List.of("matkap")), anyInt()))
                .thenReturn(List.of(matkap));

        AssistantSearchResponse response = service.search(userId, "divarda desik acan alet");

        assertThat(response.items()).containsExactly(matkap);
        assertThat(response.answer()).contains("Matkap").contains("Ev > Anbar");
        verify(searchService, never()).search(any(), anyString(), anyInt());
    }

    @Test
    void aNameTheModelInventedFallsBackToTheKeywordSearchInsteadOfAnsweringNothing() {
        // An invented name must not swallow the search. This is why the fallback tests the
        // RESOLVED rows rather than the picks: the picks were non-empty, the rows were not.
        ItemSearchResult found = new ItemSearchResult(UUID.randomUUID(), "Alet qutusu",
                List.of("Ev"), null, Instant.now());
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(1L);
        when(itemRepository.findActiveNamesByUserId(userId)).thenReturn(List.of("Alet qutusu"));
        when(aiAssistant.interpretSearch(anyString(), anyList()))
                .thenReturn(new SearchInterpretation(List.of("alet"), List.of("Perforator")));
        when(searchService.findByNames(eq(userId), eq(List.of("perforator")), anyInt()))
                .thenReturn(List.of());
        when(searchService.search(eq(userId), eq("alet"), anyInt())).thenReturn(List.of(found));

        assertThat(service.search(userId, "alet harada").items()).containsExactly(found);
    }

    @Test
    void anInventoryOverTheCapOffersNoListAtAllRatherThanATruncatedOne() {
        // Decision 2 of the design. A truncated list makes an item unfindable for a reason the
        // user can neither see nor act on; the older keyword search at least works predictably.
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(1001L);
        when(aiAssistant.interpretSearch(anyString(), anyList()))
                .thenReturn(new SearchInterpretation(List.of("pasport"), null));
        when(searchService.search(eq(userId), eq("pasport"), anyInt())).thenReturn(List.of());

        service.search(userId, "pasport harada");

        verify(aiAssistant).interpretSearch("pasport harada", List.of());
        verify(itemRepository, never()).findActiveNamesByUserId(any());
    }

    @Test
    void theCountIsWhatGatesTheRead() {
        // The names are never loaded for an account that would not send them — one cheap COUNT
        // decides, and it is the same scoped query the plan limits already use.
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(5L);
        when(itemRepository.findActiveNamesByUserId(userId)).thenReturn(List.of("Pasport"));
        when(aiAssistant.interpretSearch(anyString(), anyList()))
                .thenReturn(new SearchInterpretation(List.of("pasport"), null));
        when(searchService.search(eq(userId), eq("pasport"), anyInt())).thenReturn(List.of());

        service.search(userId, "pasport harada");

        verify(itemRepository).countByUserIdAndArchivedFalse(userId);
        verify(itemRepository).findActiveNamesByUserId(userId);
    }

    @Test
    void theSearchRowRecordsHowManyNamesWereOfferedAndWhichWerePicked() {
        ItemSearchResult matkap = new ItemSearchResult(UUID.randomUUID(), "Matkap",
                List.of("Ev"), null, Instant.now());
        when(itemRepository.countByUserIdAndArchivedFalse(userId)).thenReturn(2L);
        when(itemRepository.findActiveNamesByUserId(userId)).thenReturn(List.of("Matkap", "Pasport"));
        when(aiAssistant.interpretSearch(anyString(), anyList()))
                .thenReturn(new SearchInterpretation(List.of("alet"), List.of("Matkap")));
        when(searchService.findByNames(eq(userId), anyList(), anyInt())).thenReturn(List.of(matkap));

        service.search(userId, "divarda desik acan alet");

        InterpretationSnapshot snapshot = recordedRow(AssistantOutcome.ANSWERED).draft().interpretation();
        // The COUNT, never the names: a thousand item names on every search row would copy the
        // whole inventory into this table over and over.
        assertThat(snapshot.offeredItemCount()).isEqualTo(2);
        assertThat(snapshot.matches()).containsExactly("matkap");
    }

    @Test
    void searchFallsBackToRawQueryWhenAiIsDown() {
        when(aiAssistant.interpretSearch(anyString(), anyList())).thenThrow(new AiAssistantException("down"));
        when(searchService.search(eq(userId), eq("passport"), anyInt())).thenReturn(List.of());

        AssistantSearchResponse response = service.search(userId, "Passport");

        assertThat(response.answer()).contains("couldn't find");
        verify(searchService).search(eq(userId), eq("passport"), anyInt());
    }

    @Test
    void searchAnswerIsComposedFromRetrievedRecordsOnly() {
        when(aiAssistant.interpretSearch(anyString(), anyList()))
                .thenReturn(new SearchInterpretation(List.of("passport"), null));
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
        when(aiAssistant.interpretSearch(anyString(), anyList()))
                .thenReturn(new SearchInterpretation(List.of("passport", "Passport", "travel document"), null));
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
        when(aiAssistant.interpretSearch(anyString(), anyList())).thenReturn(new SearchInterpretation(List.of(), null));
        when(searchService.search(eq(userId), eq("passport"), anyInt())).thenReturn(List.of());

        service.search(userId, "Passport");

        AssistantMessageDraft draft = recordedDraft(AssistantOutcome.ANSWERED);
        assertThat(draft.interpretation().keywords()).containsExactly("passport");
        assertThat(draft.interpretation().usedFallback()).isTrue();
    }

    @Test
    void searchWithNothingUsableAndNoFallbackRecordsNotUnderstoodAndRunsNoSearch() {
        // "!" is dropped by the validator and "a" is too short for the normalize fallback.
        when(aiAssistant.interpretSearch(anyString(), anyList())).thenReturn(new SearchInterpretation(List.of("!"), null));

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
        when(aiAssistant.interpretSearch(anyString(), anyList())).thenThrow(new AiAssistantException("down"));
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

    // ---- BR-7: a pinned locationId — no model, the text is the name ----------------------------

    /**
     * The decision this path rests on: once the place is chosen there is nothing to interpret, so
     * the provider is not called at all. Asserting {@code verifyNoInteractions(aiAssistant)} is the
     * whole feature — it is what makes the path free, instant, and incapable of misreading
     * "kabel 20A" as anything other than "kabel 20A".
     */
    @Test
    void aPinnedLocationTakesTheTextAsTheItemNameAndNeverCallsTheModel() {
        UUID pinned = UUID.randomUUID();
        UUID space = UUID.randomUUID();
        when(executor.placeAt(eq(userId), eq(pinned), eq("kabel 20A"), eq(null),
                eq(AssistantService.PLACEMENT_NOTE)))
                .thenReturn(new PlacementExecutor.ExecutionResult(
                        item("kabel 20A", List.of("Xalqlar", "Ashagi kladovka", "Karobka")), List.of(), space));

        RememberResponse response = service.remember(userId, "kabel 20A", null, pinned);

        assertThat(response.status()).isEqualTo(RememberResponse.Status.CREATED);
        assertThat(response.item().name()).isEqualTo("kabel 20A");
        assertThat(response.createdLocations()).isEmpty();
        assertThat(response.message()).isEqualTo("Saved. kabel 20A is in Xalqlar > Ashagi kladovka > Karobka.");
        verifyNoInteractions(aiAssistant);
        verifyNoInteractions(spaceRepository);
        verify(executor, never()).place(any(), any(), any(), any());
    }

    /**
     * The accepted consequence of "no syntax analysis", pinned here so it is a decision rather than
     * a surprise: with a pin, a full sentence becomes an item named after the whole sentence. The
     * user asked for exactly this — the alternative is the interpretation that went wrong — and the
     * remedy is the Undo the created card already offers.
     */
    @Test
    void aPinnedSentenceIsFiledUnderItsOwnWordsWithoutBeingParsed() {
        UUID pinned = UUID.randomUUID();
        when(executor.placeAt(eq(userId), eq(pinned), eq("termosu karobkaya qoydum"), eq(null), anyString()))
                .thenReturn(new PlacementExecutor.ExecutionResult(
                        item("termosu karobkaya qoydum", List.of("Xalqlar", "Karobka")),
                        List.of(), UUID.randomUUID()));

        RememberResponse response = service.remember(userId, "termosu karobkaya qoydum", null, pinned);

        assertThat(response.status()).isEqualTo(RememberResponse.Status.CREATED);
        assertThat(response.item().name()).isEqualTo("termosu karobkaya qoydum");
        verifyNoInteractions(aiAssistant);
    }

    /**
     * A question typed into Remember mode with a pin set. It is kept out by the item-name charset
     * rather than by any language understanding — SAFE_NAME has never allowed '?' — which is why
     * this defence survives without a model and in every language.
     */
    @Test
    void aPinnedTextThatCannotBeAnItemNameIsNotUnderstoodAndWritesNothing() {
        UUID pinned = UUID.randomUUID();

        RememberResponse response = service.remember(userId, "kabel haradadir?", null, pinned);

        assertThat(response.status()).isEqualTo(RememberResponse.Status.NOT_UNDERSTOOD);
        assertThat(response.message()).contains("exactly as typed");
        verifyNoInteractions(aiAssistant);
        verify(executor, never()).placeAt(any(), any(), any(), any(), any());
        // The sentence is still kept: the row is the record of why nothing happened.
        AssistantMessageDraft draft = recordedDraft(AssistantOutcome.NOT_UNDERSTOOD);
        assertThat(draft.message()).isEqualTo("kabel haradadir?");
        assertThat(draft.interpretation()).isNull();
    }

    @Test
    void aPinnedTextTooLongToBeAnItemNameIsNotUnderstood() {
        UUID pinned = UUID.randomUUID();

        RememberResponse response = service.remember(userId, "k".repeat(121), null, pinned);

        assertThat(response.status()).isEqualTo(RememberResponse.Status.NOT_UNDERSTOOD);
        verify(executor, never()).placeAt(any(), any(), any(), any(), any());
    }

    /**
     * The provenance row has to say that no model ran, and say it in a way a query can separate
     * from a provider failure: {@code provider = 'none'} with a NULL interpretation is this path,
     * a NULL interpretation with a real provider is a failure.
     */
    @Test
    void aPinnedRowRecordsNoProviderAndNoInterpretation() {
        UUID pinned = UUID.randomUUID();
        UUID space = UUID.randomUUID();
        ItemResponse created = item("kabel 20A", List.of("Xalqlar", "Karobka"));
        when(executor.placeAt(eq(userId), eq(pinned), anyString(), eq(null), anyString()))
                .thenReturn(new PlacementExecutor.ExecutionResult(created, List.of(), space));

        service.remember(userId, "kabel 20A", null, pinned);

        RecordedRow row = recordedRow(AssistantOutcome.CREATED);
        assertThat(row.draft().ai()).isEqualTo(AiMetadata.NONE);
        assertThat(row.draft().ai().provider()).isEqualTo("none");
        assertThat(row.draft().interpretation()).isNull();
        assertThat(row.draft().confidence()).isNull();
        assertThat(row.result().itemId()).isEqualTo(created.id());
        assertThat(row.result().spaceId()).isEqualTo(space);
    }

    @Test
    void aPinnedLocationThatIsNotThisUsersRecordsFailedWithNoSpaceAndRethrows() {
        UUID pinned = UUID.randomUUID();
        when(executor.placeAt(eq(userId), eq(pinned), anyString(), eq(null), anyString()))
                .thenThrow(new NotFoundException(ErrorCode.LOCATION_NOT_FOUND, "Location not found"));

        assertThatThrownBy(() -> service.remember(userId, "kabel 20A", null, pinned))
                .isInstanceOf(NotFoundException.class);

        AssistantMessageResult result = recordedRow(AssistantOutcome.FAILED).result();
        assertThat(result.errorCode()).isEqualTo(ErrorCode.LOCATION_NOT_FOUND.name());
        // No space link: the ownership check that would have revealed the space is what threw.
        assertThat(result.spaceId()).isNull();
    }

    /**
     * A location already names its space, so the pair can only be redundant or contradictory.
     * Rejecting it keeps precedence out of the contract.
     */
    @Test
    void sendingBothASpaceIdAndALocationIdIsARejectedContradiction() {
        assertThatThrownBy(() -> service.remember(userId, "I put my passport in the drawer",
                UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not both");

        verifyNoInteractions(aiAssistant, spaceRepository, executor, messages);
    }

    // ------------------------------------------------------- free-tier limit on both remember paths

    /**
     * The item limit is enforced inside {@code ItemService.createAt}, i.e. inside the executor's
     * transaction, so from here a refusal is indistinguishable from any other rolled-back placement:
     * FAILED, with the refusal's own error code. That is the answer to "what does a limit refusal
     * record" — FAILED / PLAN_LIMIT_REACHED, never CREATED and never a silent NOT_UNDERSTOOD.
     */
    @Test
    void aChainPlacementRefusedByThePlanLimitRecordsFailedWithThatCodeAndRethrows() {
        Space home = space("Home");
        when(aiAssistant.interpretPlacement(anyString(), anyList())).thenReturn(interpretation("Home", 0.93));
        when(spaceRepository.findByUserIdAndNormalizedName(userId, "home")).thenReturn(Optional.of(home));
        when(executor.place(eq(userId), eq(home.getId()), any(), eq(AssistantService.PLACEMENT_NOTE)))
                .thenThrow(PlanLimitReachedException.activeItems(100, Plan.FREE, true));

        assertThatThrownBy(() ->
                service.remember(userId, "I put my passport in the bedroom drawer at home", null, null))
                .isInstanceOf(PlanLimitReachedException.class)
                .hasMessageContaining("100 active items");

        RecordedRow row = recordedRow(AssistantOutcome.FAILED);
        assertThat(row.result().errorCode()).isEqualTo(ErrorCode.PLAN_LIMIT_REACHED.name());
        assertThat(row.result().itemId()).isNull();
        // The space was resolved before the executor ran, so that half of the diagnosis survives.
        assertThat(row.result().spaceId()).isEqualTo(home.getId());
        assertThat(row.draft().message()).isEqualTo("I put my passport in the bedroom drawer at home");
    }

    @Test
    void aPinnedPlacementRefusedByThePlanLimitRecordsFailedWithThatCodeAndRethrows() {
        UUID pinned = UUID.randomUUID();
        when(executor.placeAt(eq(userId), eq(pinned), anyString(), eq(null), anyString()))
                .thenThrow(PlanLimitReachedException.activeItems(100, Plan.FREE, true));

        assertThatThrownBy(() -> service.remember(userId, "kabel 20A", null, pinned))
                .isInstanceOf(PlanLimitReachedException.class);

        RecordedRow row = recordedRow(AssistantOutcome.FAILED);
        assertThat(row.result().errorCode()).isEqualTo(ErrorCode.PLAN_LIMIT_REACHED.name());
        // Same shape as any other pinned failure: no provider ran, so no interpretation and no
        // space link (the executor's own lookup is what would have revealed the space).
        assertThat(row.draft().ai()).isEqualTo(AiMetadata.NONE);
        assertThat(row.draft().interpretation()).isNull();
        assertThat(row.result().spaceId()).isNull();
        verifyNoInteractions(aiAssistant);
    }
}
