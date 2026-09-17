package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.assistant.dto.RememberRequest;
import az.technest.whereis.assistant.dto.RememberResponse;
import az.technest.whereis.common.error.ApiError;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.SpaceResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * BR-7: a pinned {@code locationId} settles the destination before the request starts.
 *
 * <p>The claim being tested is stronger than "the item goes to the right place". It is that this
 * path cannot touch the location tree at all: {@code resolveOrCreateChain} — the only
 * auto-creation path in the system — is never entered, so the tree is byte-identical before and
 * after, no matter what the model says the sentence meant. Every test here therefore asserts the
 * tree as well as the item, because an assertion on the item alone would still pass if the chain
 * had been created and then ignored.
 *
 * <p>The {@code mock} provider is what makes this checkable offline: it deterministically segments
 * "I put my passport in the bedroom wardrobe top drawer" into Bedroom &gt; Wardrobe &gt; Top Drawer,
 * which is exactly the chain that must NOT appear.
 */
class AssistantPinnedLocationIT extends AbstractIntegrationTest {

    private static final String REMEMBER = "/api/v1/assistant/remember";
    private static final String SENTENCE = "I put my passport in the bedroom wardrobe top drawer";

    private List<String> locationNamesIn(UUID spaceId) {
        return jdbc.queryForList("select name from locations where space_id = ? order by name", String.class, spaceId);
    }

    @Test
    void aPinnedLocationTakesTheItemAndTheSentencesChainIsNeverCreated() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse box = createLocation(token, home.id(), "Box", LocationType.BOX, null);
        assertThat(locationNamesIn(home.id())).containsExactly("Box");

        ResponseEntity<RememberResponse> response = post(token, REMEMBER,
                new RememberRequest(SENTENCE, null, box.id()), RememberResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RememberResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(RememberResponse.Status.CREATED);
        assertThat(body.item().name()).isEqualTo("Passport");
        assertThat(body.item().currentLocationId()).isEqualTo(box.id());
        // locationPath opens with the space name, then the locations.
        assertThat(body.item().locationPath()).containsExactly("Home", "Box");
        assertThat(body.createdLocations()).isEmpty();
        // The sentence's own place is reported so a stale pin is visible — and not obeyed.
        assertThat(body.messagePlaceIgnored()).isTrue();
        assertThat(body.message()).contains("ignored");
        // The point of the whole feature: the tree did not move.
        assertThat(locationNamesIn(home.id())).containsExactly("Box");
    }

    @Test
    void aPinThatTheSentenceAlsoNamesIsNotReportedAsAnOverride() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse drawer = createLocation(token, home.id(), "Top Drawer", LocationType.DRAWER, null);

        RememberResponse body = post(token, REMEMBER,
                new RememberRequest(SENTENCE, null, drawer.id()), RememberResponse.class).getBody();

        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(RememberResponse.Status.CREATED);
        assertThat(body.messagePlaceIgnored()).isFalse();
        assertThat(body.message()).doesNotContain("ignored");
        assertThat(locationNamesIn(home.id())).containsExactly("Top Drawer");
    }

    @Test
    void theProvenanceRowCarriesTheSpaceOfThePinNotTheOneTheSentenceNamed() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        SpaceResponse xalqlar = createSpace(token, "Xalqlar", SpaceType.HOME);
        // A second space the sentence will name and the pin will beat.
        createSpace(token, "Warehouse", SpaceType.WAREHOUSE);
        LocationResponse box = createLocation(token, xalqlar.id(), "Karobka", LocationType.BOX, null);

        RememberResponse body = post(token, REMEMBER,
                new RememberRequest("I put my drill in the box at the warehouse", null, box.id()),
                RememberResponse.class).getBody();

        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(RememberResponse.Status.CREATED);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select outcome, space_id, item_id,
                       interpretation->>'spaceName'     as space_name,
                       interpretation->>'offeredSpaces' as offered_spaces
                from assistant_messages where user_id = ?
                """, userId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("outcome", "CREATED")
                .containsEntry("space_id", xalqlar.id())
                .containsEntry("item_id", body.item().id());
        // What the model would have answered is kept for comparison — this is the labelled
        // evidence the pinned path exists to produce, and it must not be the resolved space.
        assertThat(rows.getFirst().get("space_name")).isEqualTo("Warehouse");
        assertThat((String) rows.getFirst().get("offered_spaces")).contains("Xalqlar").contains("Warehouse");
    }

    @Test
    void someoneElsesLocationIsANotFoundAndWritesNothing() {
        String owner = registerAndGetToken();
        SpaceResponse theirSpace = createSpace(owner, "Home", SpaceType.HOME);
        LocationResponse theirBox = createLocation(owner, theirSpace.id(), "Box", LocationType.BOX, null);

        String intruder = registerAndGetToken();
        UUID intruderId = subjectOf(intruder);
        createSpace(intruder, "Home", SpaceType.HOME);

        ResponseEntity<ApiError> response = post(intruder, REMEMBER,
                new RememberRequest(SENTENCE, null, theirBox.id()), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("select count(*) from items where user_id = ?", Long.class, intruderId))
                .isZero();
        assertThat(locationNamesIn(theirSpace.id())).containsExactly("Box");
        // A FAILED row is kept: unlike a foreign spaceId (rejected before any resolution), the
        // location lookup happens inside the executor, so the request DID reach the model.
        assertThat(jdbc.queryForList(
                "select outcome, space_id from assistant_messages where user_id = ?", intruderId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row).containsEntry("outcome", "FAILED");
                    assertThat(row.get("space_id")).isNull();
                });
    }

    @Test
    void sendingBothASpaceIdAndALocationIdIsRejectedBeforeAnythingHappens() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse box = createLocation(token, home.id(), "Box", LocationType.BOX, null);

        ResponseEntity<ApiError> response = post(token, REMEMBER,
                new RememberRequest(SENTENCE, home.id(), box.id()), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("select count(*) from items where user_id = ?", Long.class, userId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from assistant_messages where user_id = ?", Long.class, userId)).isZero();
    }
}
