package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.assistant.dto.AssistantSearchRequest;
import az.technest.whereis.assistant.dto.AssistantSearchResponse;
import az.technest.whereis.assistant.dto.RememberRequest;
import az.technest.whereis.assistant.dto.RememberResponse;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.item.dto.MoveItemRequest;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.SpaceResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The complete MVP user journey from the spec (section 29), end to end,
 * with the deterministic mock AI provider.
 */
class MvpJourneyIT extends AbstractIntegrationTest {

    @Test
    void registerRememberSearchMoveHistory() {
        // 1. User registers and creates "Home".
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);

        // 2. Natural-language registration: interpretation -> validation -> resolution -> creation.
        ResponseEntity<RememberResponse> remembered = post(token, "/api/v1/assistant/remember",
                new RememberRequest("I put my passport in the bedroom wardrobe top drawer"),
                RememberResponse.class);
        assertThat(remembered.getStatusCode()).isEqualTo(HttpStatus.OK);
        RememberResponse remember = remembered.getBody();
        assertThat(remember.status()).isEqualTo(RememberResponse.Status.CREATED);
        assertThat(remember.createdLocations()).containsExactly("Bedroom", "Wardrobe", "Top Drawer");
        ItemResponse passport = remember.item();
        assertThat(passport.name()).isEqualTo("Passport");
        assertThat(passport.locationPath()).containsExactly("Home", "Bedroom", "Wardrobe", "Top Drawer");

        // The auto-created chain is real, queryable data.
        ResponseEntity<JsonNode> tree = get(token, "/api/v1/spaces/" + home.id() + "/location-tree", JsonNode.class);
        assertThat(tree.getBody().get(0).get("name").asText()).isEqualTo("Bedroom");

        // 3. "Where is my passport?" — answered from database records only.
        AssistantSearchResponse found = post(token, "/api/v1/assistant/search",
                new AssistantSearchRequest("Where is my passport?"), AssistantSearchResponse.class).getBody();
        assertThat(found.answer()).isEqualTo("Your Passport is in Home > Bedroom > Wardrobe > Top Drawer.");
        assertThat(found.items()).hasSize(1);

        // 4. A second space appears; the assistant now refuses to guess between spaces.
        SpaceResponse office = createSpace(token, "Office", SpaceType.OFFICE);
        RememberResponse ambiguous = post(token, "/api/v1/assistant/remember",
                new RememberRequest("I put my charger in the desk drawer"), RememberResponse.class).getBody();
        assertThat(ambiguous.status()).isEqualTo(RememberResponse.Status.NEEDS_CONFIRMATION);
        assertThat(ambiguous.candidateSpaces()).hasSize(2);

        // 5. The user moves the passport to the office desk drawer (explicit move).
        LocationResponse desk = createLocation(token, office.id(), "Desk", LocationType.DESK, null);
        LocationResponse deskDrawer = createLocation(token, office.id(), "Drawer", LocationType.DRAWER, desk.id());
        ItemResponse moved = post(token, "/api/v1/items/" + passport.id() + "/move",
                new MoveItemRequest(deskDrawer.id(), "Took it to the office"), ItemResponse.class).getBody();
        assertThat(moved.locationPath()).containsExactly("Office", "Desk", "Drawer");

        // 6. Current location updated; previous history preserved.
        JsonNode history = get(token, "/api/v1/items/" + passport.id() + "/history", JsonNode.class).getBody();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("removedAt").isNull()).isTrue();
        assertThat(history.get(0).get("locationPath").asText()).isEqualTo("Office > Desk > Drawer");
        assertThat(history.get(1).get("removedAt").isNull()).isFalse();
        assertThat(history.get(1).get("locationPath").asText())
                .isEqualTo("Home > Bedroom > Wardrobe > Top Drawer");

        // 7. Search now reports the new location.
        AssistantSearchResponse foundAgain = post(token, "/api/v1/assistant/search",
                new AssistantSearchRequest("Where is my passport?"), AssistantSearchResponse.class).getBody();
        assertThat(foundAgain.answer()).isEqualTo("Your Passport is in Office > Desk > Drawer.");

        // 8. Another user sees none of it.
        String mallory = registerAndGetToken();
        assertThat(get(mallory, "/api/v1/items/" + passport.id(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        AssistantSearchResponse empty = post(mallory, "/api/v1/assistant/search",
                new AssistantSearchRequest("Where is my passport?"), AssistantSearchResponse.class).getBody();
        assertThat(empty.items()).isEmpty();
    }

    @Test
    void hallucinationSafety_unknownSpaceNeverCreatesAnything() {
        String token = registerAndGetToken();
        createSpace(token, "Home", SpaceType.HOME);
        createSpace(token, "Office", SpaceType.OFFICE);

        // The mock reports space "Home"? No — "at the warehouse" resolves to a space
        // the user does NOT have. The assistant must ask, not create.
        RememberResponse response = post(token, "/api/v1/assistant/remember",
                new RememberRequest("I put my drill in the box at the warehouse"),
                RememberResponse.class).getBody();

        assertThat(response.status()).isEqualTo(RememberResponse.Status.NEEDS_CONFIRMATION);
        assertThat(response.item()).isNull();

        // No third space and no items were created behind the user's back.
        JsonNode spaces = get(token, "/api/v1/spaces", JsonNode.class).getBody();
        assertThat(spaces).hasSize(2);
        JsonNode items = get(token, "/api/v1/items", JsonNode.class).getBody();
        assertThat(items.get("totalElements").asInt()).isZero();
    }

    @Test
    void imageAnalysisSuggestsButNeverPersists() {
        String token = registerAndGetToken();
        org.springframework.util.LinkedMultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        org.springframework.http.HttpHeaders partHeaders = new org.springframework.http.HttpHeaders();
        partHeaders.setContentType(org.springframework.http.MediaType.IMAGE_JPEG);
        body.add("file", new org.springframework.http.HttpEntity<>(
                new org.springframework.core.io.ByteArrayResource(
                        new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4, 5, 6, 7, 8}) {
                    @Override
                    public String getFilename() {
                        return "drawer.jpg";
                    }
                }, partHeaders));
        org.springframework.http.HttpHeaders headers = bearer(token);
        headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<JsonNode> analyzed = rest.exchange("/api/v1/assistant/images/analyze",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(body, headers), JsonNode.class);

        assertThat(analyzed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(analyzed.getBody().get("suggestions")).isNotEmpty();

        JsonNode items = get(token, "/api/v1/items", JsonNode.class).getBody();
        assertThat(items.get("totalElements").asInt()).isZero();
    }
}
