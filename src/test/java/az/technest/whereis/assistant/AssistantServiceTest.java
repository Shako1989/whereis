package az.technest.whereis.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import az.technest.whereis.assistant.dto.AssistantSearchResponse;
import az.technest.whereis.assistant.dto.RememberResponse;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssistantServiceTest {

    @Mock
    private AiAssistant aiAssistant;
    @Mock
    private SpaceRepository spaceRepository;
    @Mock
    private PlacementExecutor executor;
    @Mock
    private SearchService searchService;

    private AssistantService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AssistantService(aiAssistant, new InterpretationValidator(),
                spaceRepository, executor, searchService);
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

    @Test
    void lowConfidenceInterpretationNeverMutatesAnything() {
        when(aiAssistant.interpretPlacement(anyString())).thenReturn(interpretation(null, 0.1));

        RememberResponse response = service.remember(userId, "gibberish");

        assertThat(response.status()).isEqualTo(RememberResponse.Status.NOT_UNDERSTOOD);
        verify(executor, never()).place(any(), any(), any(), any());
    }

    @Test
    void hallucinatedSpaceNameIsNotTrusted() {
        // The AI names a space the user does not have — nothing may be created.
        when(aiAssistant.interpretPlacement(anyString())).thenReturn(interpretation("Moon Base", 0.95));
        when(spaceRepository.findByUserIdAndNormalizedName(userId, "moon base")).thenReturn(Optional.empty());
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId)).thenReturn(List.of(space("Home")));

        RememberResponse response = service.remember(userId, "I put my passport in the moon base drawer");

        assertThat(response.status()).isEqualTo(RememberResponse.Status.NEEDS_CONFIRMATION);
        assertThat(response.candidateSpaces()).hasSize(1);
        verify(executor, never()).place(any(), any(), any(), any());
    }

    @Test
    void multipleSpacesWithoutExplicitNameNeedConfirmation() {
        when(aiAssistant.interpretPlacement(anyString())).thenReturn(interpretation(null, 0.9));
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId))
                .thenReturn(List.of(space("Home"), space("Office")));

        RememberResponse response = service.remember(userId, "I put my passport in the bedroom drawer");

        assertThat(response.status()).isEqualTo(RememberResponse.Status.NEEDS_CONFIRMATION);
        assertThat(response.candidateSpaces()).hasSize(2);
        verify(executor, never()).place(any(), any(), any(), any());
    }

    @Test
    void zeroSpacesNeedsConfirmationWithGuidance() {
        when(aiAssistant.interpretPlacement(anyString())).thenReturn(interpretation(null, 0.9));
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId)).thenReturn(List.of());

        RememberResponse response = service.remember(userId, "I put my passport in the bedroom drawer");

        assertThat(response.status()).isEqualTo(RememberResponse.Status.NEEDS_CONFIRMATION);
        assertThat(response.candidateSpaces()).isEmpty();
        verify(executor, never()).place(any(), any(), any(), any());
    }

    @Test
    void singleSpaceResolvesAutomaticallyAndPlaces() {
        Space home = space("Home");
        when(aiAssistant.interpretPlacement(anyString())).thenReturn(interpretation(null, 0.9));
        when(spaceRepository.findAllByUserIdOrderByNameAsc(userId)).thenReturn(List.of(home));
        ItemResponse item = new ItemResponse(UUID.randomUUID(), "Passport", null, null, UUID.randomUUID(),
                List.of("Home", "Bedroom", "Top Drawer"), false, Instant.now(), Instant.now());
        when(executor.place(eq(userId), eq(home.getId()), any(), anyString()))
                .thenReturn(new PlacementExecutor.ExecutionResult(item, List.of("Bedroom", "Top Drawer")));

        RememberResponse response = service.remember(userId, "I put my passport in the bedroom top drawer");

        assertThat(response.status()).isEqualTo(RememberResponse.Status.CREATED);
        assertThat(response.item().name()).isEqualTo("Passport");
        assertThat(response.createdLocations()).containsExactly("Bedroom", "Top Drawer");
    }

    @Test
    void explicitSpaceNameResolvesAgainstTheDatabase() {
        Space home = space("Home");
        when(aiAssistant.interpretPlacement(anyString())).thenReturn(interpretation("Home", 0.9));
        when(spaceRepository.findByUserIdAndNormalizedName(userId, "home")).thenReturn(Optional.of(home));
        ItemResponse item = new ItemResponse(UUID.randomUUID(), "Passport", null, null, UUID.randomUUID(),
                List.of("Home", "Bedroom", "Top Drawer"), false, Instant.now(), Instant.now());
        when(executor.place(eq(userId), eq(home.getId()), any(), anyString()))
                .thenReturn(new PlacementExecutor.ExecutionResult(item, List.of()));

        assertThat(service.remember(userId, "I put my passport in the bedroom drawer at home").status())
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
}
