package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.SpaceResponse;
import az.technest.whereis.storage.dto.ItemFileResponse;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * BR-3: the items list carries each item's cover photo — the primary photo, else the oldest
 * upload — resolved in one batch query per page instead of one {@code GET /items/{id}/files}
 * per row.
 */
class ItemListCoverPhotoIT extends AbstractIntegrationTest {

    private static final byte[] JPEG_BYTES =
            {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 10, 20, 30, 40, 50, 60, 70, 80};

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private UUID createItem(String token, UUID locationId, String name) {
        return post(token, "/api/v1/items", new CreateItemRequest(name, null, null, locationId),
                ItemResponse.class).getBody().id();
    }

    private UUID uploadPrimaryPhoto(String token, UUID itemId) {
        return uploadPhoto(token, itemId, true);
    }

    private UUID uploadPhoto(String token, UUID itemId, boolean primary) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.IMAGE_JPEG);
        body.add("file", new HttpEntity<>(new ByteArrayResource(JPEG_BYTES) {
            @Override
            public String getFilename() {
                return "cover.jpg";
            }
        }, partHeaders));
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ItemFileResponse> uploaded = rest.exchange(
                "/api/v1/items/" + itemId + "/files?primary=" + primary, HttpMethod.POST,
                new HttpEntity<>(body, headers), ItemFileResponse.class);
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return uploaded.getBody().id();
    }

    private JsonNode itemNamed(JsonNode page, String name) {
        for (JsonNode node : page.get("content")) {
            if (name.equals(node.get("name").asText())) {
                return node;
            }
        }
        throw new AssertionError("Item '" + name + "' is not on the page");
    }

    @Test
    void listCarriesTheCoverPhotoOfEveryItemThatHasOne() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse drawer = createLocation(token, home.id(), "Drawer", LocationType.DRAWER, null);
        UUID withPhoto = createItem(token, drawer.id(), "Passport");
        createItem(token, drawer.id(), "Keys");
        UUID fileId = uploadPrimaryPhoto(token, withPhoto);

        JsonNode page = get(token, "/api/v1/items?size=20", JsonNode.class).getBody();

        JsonNode passport = itemNamed(page, "Passport");
        // Both fields travel together: the id is stable, the presigned URL expires in ~10 minutes.
        assertThat(passport.get("primaryFileId").asText()).isEqualTo(fileId.toString());
        assertThat(passport.get("primaryImageUrl").asText()).startsWith("http");
        JsonNode keys = itemNamed(page, "Keys");
        assertThat(keys.get("primaryFileId").isNull()).isTrue();
        assertThat(keys.get("primaryImageUrl").isNull()).isTrue();

        // GET /items/{id} carries it too — the detail screen needs no extra call either.
        ItemResponse single = get(token, "/api/v1/items/" + withPhoto, ItemResponse.class).getBody();
        assertThat(single.primaryFileId()).isEqualTo(fileId);
        assertThat(single.primaryImageUrl()).startsWith("http");
    }

    @Test
    void anItemWhoseOnlyPhotosAreNonPrimaryUsesItsOldestOneAsCover() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse drawer = createLocation(token, home.id(), "Drawer", LocationType.DRAWER, null);
        UUID termos = createItem(token, drawer.id(), "Termos");
        // Legacy shape: the old Android client uploaded with primary=false by default, so most
        // real items have photos and no primary. The retired client showed the FIRST photo.
        UUID firstUploaded = uploadPhoto(token, termos, false);
        UUID secondUploaded = uploadPhoto(token, termos, false);

        JsonNode page = get(token, "/api/v1/items?size=20", JsonNode.class).getBody();

        JsonNode row = itemNamed(page, "Termos");
        assertThat(row.get("primaryFileId").asText()).isEqualTo(firstUploaded.toString());
        assertThat(row.get("primaryFileId").asText()).isNotEqualTo(secondUploaded.toString());
        assertThat(row.get("primaryImageUrl").asText()).startsWith("http");
        ItemResponse single = get(token, "/api/v1/items/" + termos, ItemResponse.class).getBody();
        assertThat(single.primaryFileId()).isEqualTo(firstUploaded);
    }

    @Test
    void aPrimaryPhotoBeatsAnOlderNonPrimaryOne() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse drawer = createLocation(token, home.id(), "Drawer", LocationType.DRAWER, null);
        UUID passport = createItem(token, drawer.id(), "Passport");
        UUID olderPlain = uploadPhoto(token, passport, false);
        UUID newerPrimary = uploadPhoto(token, passport, true);

        JsonNode page = get(token, "/api/v1/items?size=20", JsonNode.class).getBody();

        JsonNode row = itemNamed(page, "Passport");
        // An explicit choice always wins over the age-based fallback.
        assertThat(row.get("primaryFileId").asText()).isEqualTo(newerPrimary.toString());
        assertThat(row.get("primaryFileId").asText()).isNotEqualTo(olderPlain.toString());
    }

    @Test
    void anItemWithoutPhotosHasNullCoverFields() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse drawer = createLocation(token, home.id(), "Drawer", LocationType.DRAWER, null);
        UUID keys = createItem(token, drawer.id(), "Keys");

        JsonNode row = itemNamed(get(token, "/api/v1/items?size=20", JsonNode.class).getBody(), "Keys");
        assertThat(row.get("primaryFileId").isNull()).isTrue();
        assertThat(row.get("primaryImageUrl").isNull()).isTrue();
        ItemResponse single = get(token, "/api/v1/items/" + keys, ItemResponse.class).getBody();
        assertThat(single.primaryFileId()).isNull();
        assertThat(single.primaryImageUrl()).isNull();
    }

    @Test
    void theQueryCountOfAPageDoesNotGrowWithPageSize() {
        String token = registerAndGetToken();
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse drawer = createLocation(token, home.id(), "Drawer", LocationType.DRAWER, null);
        for (int i = 0; i < 8; i++) {
            UUID itemId = createItem(token, drawer.id(), "Item " + i);
            // Every item has a cover, so a per-row lookup would be unmistakable in the counts.
            uploadPrimaryPhoto(token, itemId);
        }

        // Hibernate statistics count JDBC statements issued through the session factory, which
        // covers the page query, its count query and the batch cover lookup. LocationTreeDao's
        // recursive CTE runs on a plain JdbcTemplate and is invisible here — it is already a
        // single batch query and is not what BR-3 changed.
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        statistics.clear();
        JsonNode smallPage = get(token, "/api/v1/items?size=2", JsonNode.class).getBody();
        long statementsForTwoRows = statistics.getPrepareStatementCount();

        statistics.clear();
        JsonNode largePage = get(token, "/api/v1/items?size=6", JsonNode.class).getBody();
        long statementsForSixRows = statistics.getPrepareStatementCount();

        assertThat(smallPage.get("content")).hasSize(2);
        assertThat(largePage.get("content")).hasSize(6);
        // Both pages are full, so both issue the same Spring Data count query; the only way the
        // totals can diverge is a per-row lookup creeping back into the list path.
        assertThat(statementsForSixRows).isEqualTo(statementsForTwoRows);
        assertThat(largePage.get("content")).allSatisfy(node ->
                assertThat(node.get("primaryImageUrl").isNull()).isFalse());
    }
}
