package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.SpaceResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class SearchIT extends AbstractIntegrationTest {

    @Test
    void findsItemsByNameAndByContainingLocation() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse bedroom = createLocation(token, home.id(), "Bedroom", LocationType.ROOM, null);
        LocationResponse wardrobe = createLocation(token, home.id(), "Wardrobe", LocationType.FURNITURE, bedroom.id());
        LocationResponse drawer = createLocation(token, home.id(), "Top Drawer", LocationType.DRAWER, wardrobe.id());
        post(token, "/api/v1/items",
                new CreateItemRequest("Passport", "travel document", "Documents", drawer.id()), ItemResponse.class);

        // Direct name match, with the full path in the result.
        ResponseEntity<JsonNode> byName = get(token, "/api/v1/items/search?q=passport", JsonNode.class);
        assertThat(byName.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = byName.getBody().get(0);
        assertThat(result.get("name").asText()).isEqualTo("Passport");
        assertThat(result.get("locationPath")).extracting(JsonNode::asText)
                .containsExactly("Home", "Bedroom", "Wardrobe", "Top Drawer");

        // Location-based match: the passport sits in a drawer INSIDE the wardrobe.
        ResponseEntity<JsonNode> byLocation = get(token, "/api/v1/items/search?q=wardrobe", JsonNode.class);
        assertThat(byLocation.getBody()).hasSize(1);

        // Trigram similarity tolerates small typos.
        ResponseEntity<JsonNode> byTypo = get(token, "/api/v1/items/search?q=pasport", JsonNode.class);
        assertThat(byTypo.getBody()).hasSize(1);
    }

    @Test
    void searchIsScopedToTheCurrentUser() {
        String alice = registerAndGetToken();
        String mallory = registerAndGetToken();
        SpaceResponse home = createSpace(alice, "Home", SpaceType.HOME);
        LocationResponse drawer = createLocation(alice, home.id(), "Drawer", LocationType.DRAWER, null);
        post(alice, "/api/v1/items",
                new CreateItemRequest("Passport", null, null, drawer.id()), ItemResponse.class);

        assertThat(get(mallory, "/api/v1/items/search?q=passport", JsonNode.class).getBody()).isEmpty();
    }

    @Test
    void tooShortQueriesAreRejected() {
        String token = registerAndGetToken();
        assertThat(get(token, "/api/v1/items/search?q=p", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
