package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.item.dto.MoveItemRequest;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.SpaceResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ItemMoveIT extends AbstractIntegrationTest {

    @Test
    void movePreservesHistoryWithExactlyOneOpenRecord() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse bedroom = createLocation(token, home.id(), "Bedroom", LocationType.ROOM, null);
        LocationResponse wardrobe = createLocation(token, home.id(), "Wardrobe", LocationType.FURNITURE, bedroom.id());
        LocationResponse office = createLocation(token, home.id(), "Office", LocationType.ROOM, null);
        LocationResponse desk = createLocation(token, home.id(), "Desk", LocationType.DESK, office.id());

        ResponseEntity<ItemResponse> created = post(token, "/api/v1/items",
                new CreateItemRequest("Passport", "Red travel passport", "Documents", wardrobe.id()),
                ItemResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ItemResponse item = created.getBody();
        assertThat(item.locationPath()).containsExactly("Home", "Bedroom", "Wardrobe");

        ResponseEntity<ItemResponse> moved = post(token, "/api/v1/items/" + item.id() + "/move",
                new MoveItemRequest(desk.id(), "Moved during room cleanup"), ItemResponse.class);
        assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(moved.getBody().locationPath()).containsExactly("Home", "Office", "Desk");

        // Re-fetch from the database — the persisted current_location_id must have changed,
        // not just the in-memory object the move response was built from.
        ItemResponse refetched = get(token, "/api/v1/items/" + item.id(), ItemResponse.class).getBody();
        assertThat(refetched.currentLocationId()).isEqualTo(desk.id());
        assertThat(refetched.locationPath()).containsExactly("Home", "Office", "Desk");

        ResponseEntity<JsonNode> history = get(token, "/api/v1/items/" + item.id() + "/history", JsonNode.class);
        JsonNode entries = history.getBody();
        assertThat(entries).hasSize(2);
        // Newest first: the open record points at the new location.
        assertThat(entries.get(0).get("removedAt").isNull()).isTrue();
        assertThat(entries.get(0).get("locationPath").asText()).isEqualTo("Home > Office > Desk");
        assertThat(entries.get(0).get("note").asText()).isEqualTo("Moved during room cleanup");
        // The previous record is closed but preserved with its snapshot.
        assertThat(entries.get(1).get("removedAt").isNull()).isFalse();
        assertThat(entries.get(1).get("locationPath").asText()).isEqualTo("Home > Bedroom > Wardrobe");

        long openRecords = 0;
        for (JsonNode entry : entries) {
            if (entry.get("removedAt").isNull()) {
                openRecords++;
            }
        }
        assertThat(openRecords).isEqualTo(1);
    }

    @Test
    void moveCannotTargetAnotherUsersLocation() {
        String alice = registerAndGetToken();
        String mallory = registerAndGetToken();
        SpaceResponse aliceHome = createSpace(alice, "Home", SpaceType.HOME);
        LocationResponse aliceDrawer = createLocation(alice, aliceHome.id(), "Drawer", LocationType.DRAWER, null);
        SpaceResponse malloryHome = createSpace(mallory, "Home", SpaceType.HOME);
        LocationResponse malloryBox = createLocation(mallory, malloryHome.id(), "Box", LocationType.BOX, null);

        ItemResponse malloryItem = post(mallory, "/api/v1/items",
                new CreateItemRequest("Gadget", null, null, malloryBox.id()), ItemResponse.class).getBody();

        // Destination ownership is enforced: moving into someone else's location is a 404.
        ResponseEntity<String> crossMove = post(mallory, "/api/v1/items/" + malloryItem.id() + "/move",
                new MoveItemRequest(aliceDrawer.id(), null), String.class);
        assertThat(crossMove.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // And nobody can read or move a foreign item.
        assertThat(get(alice, "/api/v1/items/" + malloryItem.id(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(alice, "/api/v1/items/" + malloryItem.id() + "/history", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void locationWithItemsCannotBeDeletedUntilEmptied() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse shelf = createLocation(token, home.id(), "Shelf", LocationType.SHELF, null);
        post(token, "/api/v1/items", new CreateItemRequest("Book", null, null, shelf.id()), ItemResponse.class);

        ResponseEntity<String> delete = rest.exchange("/api/v1/locations/" + shelf.id(),
                org.springframework.http.HttpMethod.DELETE,
                new org.springframework.http.HttpEntity<>(bearer(token)), String.class);

        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(delete.getBody()).contains("LOCATION_NOT_EMPTY");
    }
}
