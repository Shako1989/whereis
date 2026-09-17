package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.item.dto.UpdateItemRequest;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.SpaceResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * BR-9: {@code GET /items?locationId=} — the items stored AT one location, which is what tapping a
 * leaf in the client's location tree shows. Equality on {@code current_location_id}, never a
 * subtree walk, and a location that is not the caller's is a 404 rather than an empty page.
 */
class ItemListByLocationIT extends AbstractIntegrationTest {

    private UUID createItem(String token, UUID locationId, String name) {
        ResponseEntity<ItemResponse> created = post(token, "/api/v1/items",
                new CreateItemRequest(name, null, null, locationId), ItemResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().id();
    }

    private void archive(String token, UUID itemId, String name) {
        ResponseEntity<ItemResponse> updated = rest.exchange("/api/v1/items/" + itemId, HttpMethod.PUT,
                new HttpEntity<>(new UpdateItemRequest(name, null, null, true), bearer(token)),
                ItemResponse.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().archived()).isTrue();
    }

    private List<String> namesOnPage(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("content").forEach(node -> names.add(node.get("name").asText()));
        return names;
    }

    @Test
    void itemsAtALocationComeBackAndItemsAtASiblingDoNot() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse wardrobe = createLocation(token, home.id(), "Wardrobe", LocationType.FURNITURE, null);
        LocationResponse topDrawer = createLocation(token, home.id(), "Top drawer", LocationType.DRAWER,
                wardrobe.id());
        LocationResponse bottomDrawer = createLocation(token, home.id(), "Bottom drawer", LocationType.DRAWER,
                wardrobe.id());
        createItem(token, topDrawer.id(), "Passport");
        createItem(token, topDrawer.id(), "Keys");
        createItem(token, bottomDrawer.id(), "Winter gloves");

        JsonNode page = get(token, "/api/v1/items?locationId=" + topDrawer.id(), JsonNode.class).getBody();

        assertThat(namesOnPage(page)).containsExactlyInAnyOrder("Passport", "Keys");
        assertThat(page.get("totalElements").asInt()).isEqualTo(2);
        // The unfiltered call is unchanged and still sees everything.
        assertThat(namesOnPage(get(token, "/api/v1/items", JsonNode.class).getBody()))
                .containsExactlyInAnyOrder("Passport", "Keys", "Winter gloves");
        // The rest of the envelope is intact: paths are space-qualified, covers are present as null.
        JsonNode row = page.get("content").get(0);
        assertThat(row.get("locationPath")).isNotNull();
        assertThat(row.get("primaryFileId").isNull()).isTrue();
        assertThat(row.get("primaryImageUrl").isNull()).isTrue();
    }

    @Test
    void theFilterIsEqualityNotASubtreeWalk() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse wardrobe = createLocation(token, home.id(), "Wardrobe", LocationType.FURNITURE, null);
        LocationResponse drawer = createLocation(token, home.id(), "Top drawer", LocationType.DRAWER,
                wardrobe.id());
        createItem(token, wardrobe.id(), "Coat");
        createItem(token, drawer.id(), "Passport");

        // Asking for the parent returns ONLY what sits directly in it. The client only ever asks
        // for a leaf, so this costs it nothing; a recursive CTE here would be unused work.
        assertThat(namesOnPage(get(token, "/api/v1/items?locationId=" + wardrobe.id(), JsonNode.class)
                .getBody())).containsExactly("Coat");
        assertThat(namesOnPage(get(token, "/api/v1/items?locationId=" + drawer.id(), JsonNode.class)
                .getBody())).containsExactly("Passport");
    }

    @Test
    void aLocationThatIsNotTheCallersIsA404AndLeaksNothing() {
        String mallory = registerAndGetToken();
        SpaceResponse malloryHome = createSpace(mallory, "Home", SpaceType.HOME);
        LocationResponse malloryDrawer = createLocation(mallory, malloryHome.id(), "Safe",
                LocationType.BOX, null);
        createItem(mallory, malloryDrawer.id(), "Mallory secret passport");
        String alice = registerAndGetToken();

        ResponseEntity<String> response = get(alice, "/api/v1/items?locationId=" + malloryDrawer.id(),
                String.class);

        // §6: an ownership miss is a 404, never a 403 and never an empty page — an empty page would
        // answer "does this id exist?" for someone else's tree.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("LOCATION_NOT_FOUND");
        assertThat(response.getBody()).doesNotContain("Mallory secret passport", "content", "Safe");
        // Alice's own list is untouched by the failed probe.
        assertThat(namesOnPage(get(alice, "/api/v1/items", JsonNode.class).getBody())).isEmpty();
    }

    @Test
    void anUnknownLocationIdIsA404AndAMalformedOneIsA400() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse drawer = createLocation(token, home.id(), "Top drawer", LocationType.DRAWER, null);
        createItem(token, drawer.id(), "Passport");

        assertThat(get(token, "/api/v1/items?locationId=" + UUID.randomUUID(), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // A non-UUID never reaches the service: Spring's converter fails and the handler answers 400.
        assertThat(get(token, "/api/v1/items?locationId=not-a-uuid", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // An empty value is converted to null, i.e. no filter at all — not a 400.
        assertThat(namesOnPage(get(token, "/api/v1/items?locationId=", JsonNode.class).getBody()))
                .containsExactly("Passport");
    }

    @Test
    void theSortWhitelistAndSizeClampStillApplyWithTheFilterOn() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse drawer = createLocation(token, home.id(), "Top drawer", LocationType.DRAWER, null);
        createItem(token, drawer.id(), "First");
        createItem(token, drawer.id(), "Second");
        createItem(token, drawer.id(), "Third");

        JsonNode page = get(token, "/api/v1/items?locationId=" + drawer.id()
                + "&size=500&sort=passwordHash,asc", JsonNode.class).getBody();

        // Only the PROPERTY is whitelisted — coerced to updatedAt rather than reaching the query,
        // where it would be a 500. The requested direction survives, so oldest-first here is the
        // proof that updatedAt,asc is what ran.
        assertThat(page.get("size").asInt()).isEqualTo(100);
        assertThat(namesOnPage(page)).containsExactly("First", "Second", "Third");
        // Default sort with the filter on is still newest-first.
        assertThat(namesOnPage(get(token, "/api/v1/items?locationId=" + drawer.id(), JsonNode.class)
                .getBody())).containsExactly("Third", "Second", "First");
    }

    @Test
    void archivedItemsAtTheLocationAreHiddenUnlessAskedFor() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse drawer = createLocation(token, home.id(), "Top drawer", LocationType.DRAWER, null);
        createItem(token, drawer.id(), "Passport");
        UUID retired = createItem(token, drawer.id(), "Old charger");
        archive(token, retired, "Old charger");

        assertThat(namesOnPage(get(token, "/api/v1/items?locationId=" + drawer.id(), JsonNode.class)
                .getBody())).containsExactly("Passport");
        assertThat(namesOnPage(get(token, "/api/v1/items?locationId=" + drawer.id()
                + "&includeArchived=true", JsonNode.class).getBody()))
                .containsExactlyInAnyOrder("Passport", "Old charger");
    }
}
