package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.location.LocationType;
import az.technest.whereis.location.dto.CreateLocationRequest;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.location.dto.UpdateLocationRequest;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.SpaceResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class SpaceLocationIT extends AbstractIntegrationTest {

    @Test
    void buildsTreeEnforcesSiblingUniquenessAndRejectsCycles() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse bedroom = createLocation(token, home.id(), "Bedroom", LocationType.ROOM, null);
        LocationResponse wardrobe = createLocation(token, home.id(), "Wardrobe", LocationType.FURNITURE, bedroom.id());
        LocationResponse drawer = createLocation(token, home.id(), "Top Drawer", LocationType.DRAWER, wardrobe.id());

        // Tree endpoint returns the nested hierarchy.
        ResponseEntity<JsonNode> tree = get(token, "/api/v1/spaces/" + home.id() + "/location-tree", JsonNode.class);
        assertThat(tree.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = tree.getBody().get(0);
        assertThat(root.get("name").asText()).isEqualTo("Bedroom");
        assertThat(root.get("children").get(0).get("name").asText()).isEqualTo("Wardrobe");
        assertThat(root.get("children").get(0).get("children").get(0).get("name").asText()).isEqualTo("Top Drawer");

        // Sibling uniqueness is case/whitespace-insensitive, including for roots (NULL parent).
        ResponseEntity<String> duplicateRoot = post(token, "/api/v1/spaces/" + home.id() + "/locations",
                new CreateLocationRequest("  BEDROOM ", null, LocationType.ROOM, null), String.class);
        assertThat(duplicateRoot.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Re-parenting Bedroom under its own grandchild is a cycle.
        ResponseEntity<String> cycle = rest.exchange("/api/v1/locations/" + bedroom.id(), HttpMethod.PUT,
                new HttpEntity<>(new UpdateLocationRequest("Bedroom", null, LocationType.ROOM, drawer.id()),
                        bearer(token)), String.class);
        assertThat(cycle.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(cycle.getBody()).contains("CYCLE_DETECTED");

        // Deleting a location with children is rejected.
        ResponseEntity<String> deleteWithChildren = rest.exchange("/api/v1/locations/" + wardrobe.id(),
                HttpMethod.DELETE, new HttpEntity<>(bearer(token)), String.class);
        assertThat(deleteWithChildren.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(deleteWithChildren.getBody()).contains("LOCATION_NOT_EMPTY");

        // Deleting a non-empty space is rejected.
        ResponseEntity<String> deleteSpace = rest.exchange("/api/v1/spaces/" + home.id(),
                HttpMethod.DELETE, new HttpEntity<>(bearer(token)), String.class);
        assertThat(deleteSpace.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void usersCannotSeeOrUseEachOthersLocations() {
        String alice = registerAndGetToken();
        String mallory = registerAndGetToken();
        SpaceResponse home = createSpace(alice, "Home", SpaceType.HOME);
        LocationResponse bedroom = createLocation(alice, home.id(), "Bedroom", LocationType.ROOM, null);

        // Reads present as 404 — no existence oracle.
        assertThat(get(mallory, "/api/v1/locations/" + bedroom.id(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(mallory, "/api/v1/locations/" + bedroom.id() + "/children", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(mallory, "/api/v1/spaces/" + home.id() + "/location-tree", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Creating a location inside someone else's space is impossible.
        ResponseEntity<String> foreignCreate = post(mallory, "/api/v1/spaces/" + home.id() + "/locations",
                new CreateLocationRequest("Evil Shelf", null, LocationType.SHELF, null), String.class);
        assertThat(foreignCreate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
