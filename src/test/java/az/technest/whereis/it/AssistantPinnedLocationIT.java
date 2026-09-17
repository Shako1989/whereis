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
 * BR-7: a pinned {@code locationId} settles the destination before the request starts, and the text
 * is the item name as typed — no provider is called and nothing is parsed.
 *
 * <p>Two claims are tested, both stronger than "the item goes to the right place".
 *
 * <p>First, this path cannot touch the location tree at all: {@code resolveOrCreateChain} — the only
 * auto-creation path in the system — is never entered, so the tree is byte-identical before and
 * after. Every test asserts the tree as well as the item, because an item-only assertion would
 * still pass if a chain had been created and then ignored.
 *
 * <p>Second, no model ran. The {@code assistant_messages} row carries {@code provider = 'none'} and
 * a NULL interpretation, which is what distinguishes this path from a provider that answered and
 * from one that failed. The sentence that would have been segmented into Bedroom &gt; Wardrobe &gt;
 * Top Drawer by the {@code mock} provider is used here precisely because that chain must NOT appear.
 */
class AssistantPinnedLocationIT extends AbstractIntegrationTest {

    private static final String REMEMBER = "/api/v1/assistant/remember";
    private static final String SENTENCE = "I put my passport in the bedroom wardrobe top drawer";

    private List<String> locationNamesIn(UUID spaceId) {
        return jdbc.queryForList("select name from locations where space_id = ? order by name", String.class, spaceId);
    }

    @Test
    void aPinnedBareNameIsFiledAsTypedAndNoChainIsCreated() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        SpaceResponse xalqlar = createSpace(token, "Xalqlar", SpaceType.HOME);
        LocationResponse box = createLocation(token, xalqlar.id(), "Karobka", LocationType.BOX, null);
        assertThat(locationNamesIn(xalqlar.id())).containsExactly("Karobka");

        ResponseEntity<RememberResponse> response = post(token, REMEMBER,
                new RememberRequest("kabel 20A", null, box.id()), RememberResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RememberResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(RememberResponse.Status.CREATED);
        // As typed: no title-casing, no parsing, no stripping.
        assertThat(body.item().name()).isEqualTo("kabel 20A");
        assertThat(body.item().description()).isNull();
        assertThat(body.item().currentLocationId()).isEqualTo(box.id());
        // locationPath opens with the space name, then the locations.
        assertThat(body.item().locationPath()).containsExactly("Xalqlar", "Karobka");
        assertThat(body.createdLocations()).isEmpty();
        assertThat(locationNamesIn(xalqlar.id())).containsExactly("Karobka");

        assertThat(rowsOf(userId)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("outcome", "CREATED")
                    .containsEntry("message", "kabel 20A")
                    .containsEntry("provider", "none")
                    .containsEntry("model", "none")
                    .containsEntry("prompt_version", "none")
                    .containsEntry("space_id", xalqlar.id());
            assertThat(row.get("interpretation")).isNull();
            assertThat(row.get("confidence")).isNull();
        });
    }

    /**
     * The sentence the mock provider would have segmented, sent with a pin. It becomes one item
     * named after the whole sentence and the chain it describes is never created — the accepted
     * consequence of "no syntax analysis when a place is chosen", asserted so it stays a decision.
     */
    @Test
    void aPinnedSentenceBecomesOneItemAndItsChainIsNeverCreated() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse box = createLocation(token, home.id(), "Box", LocationType.BOX, null);

        RememberResponse body = post(token, REMEMBER,
                new RememberRequest(SENTENCE, null, box.id()), RememberResponse.class).getBody();

        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(RememberResponse.Status.CREATED);
        assertThat(body.item().name()).isEqualTo(SENTENCE);
        assertThat(body.createdLocations()).isEmpty();
        assertThat(locationNamesIn(home.id())).containsExactly("Box");
    }

    @Test
    void aPinnedTextThatCannotBeAnItemNameChangesNothingButIsStillRecorded() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse box = createLocation(token, home.id(), "Box", LocationType.BOX, null);

        RememberResponse body = post(token, REMEMBER,
                new RememberRequest("kabel haradadir?", null, box.id()), RememberResponse.class).getBody();

        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(RememberResponse.Status.NOT_UNDERSTOOD);
        assertThat(body.item()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from items where user_id = ?", Long.class, userId))
                .isZero();
        assertThat(locationNamesIn(home.id())).containsExactly("Box");
        assertThat(rowsOf(userId)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("outcome", "NOT_UNDERSTOOD")
                        .containsEntry("message", "kabel haradadir?")
                        .containsEntry("provider", "none"));
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
                new RememberRequest("kabel 20A", null, theirBox.id()), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("select count(*) from items where user_id = ?", Long.class, intruderId))
                .isZero();
        assertThat(locationNamesIn(theirSpace.id())).containsExactly("Box");
        // A FAILED row with no space: the ownership lookup inside the executor is what threw.
        assertThat(rowsOf(intruderId)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("outcome", "FAILED").containsEntry("provider", "none");
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
                new RememberRequest("kabel 20A", home.id(), box.id()), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("select count(*) from items where user_id = ?", Long.class, userId))
                .isZero();
        assertThat(rowsOf(userId)).isEmpty();
    }

    private List<Map<String, Object>> rowsOf(UUID userId) {
        return jdbc.queryForList("""
                select outcome, message, provider, model, prompt_version, confidence, space_id, item_id,
                       interpretation
                from assistant_messages where user_id = ?
                order by created_at desc, id desc
                """, userId);
    }
}
