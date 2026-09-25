package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.assistant.dto.RememberRequest;
import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.item.dto.UpdateItemRequest;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.CreateSpaceRequest;
import az.technest.whereis.space.dto.SpaceResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The free tier against a real database: 1 space and 35 ACTIVE items, refused with 409
 * PLAN_LIMIT_REACHED on all three creation paths, bypassed by an UNLIMITED grant.
 *
 * <p>These run with the REAL production limits from {@code application.yml} — no inflated test
 * values and no {@code @TestPropertySource}, which would fork the shared Spring context. The price
 * is that the 35-item boundary needs 34 items to exist first, and they are seeded with one INSERT
 * rather than 34 HTTP calls: the guard counts rows, so how the rows got there is irrelevant to what
 * is being measured (the seeded rows have no history record, which nothing here reads).
 */
class PlanLimitIT extends AbstractIntegrationTest {

    private static final String ITEMS = "/api/v1/items";
    private static final String REMEMBER = "/api/v1/assistant/remember";
    private static final String PASSPORT_SENTENCE = "I put my passport in the bedroom wardrobe top drawer";

    private String planOf(UUID userId) {
        return jdbc.queryForObject("select plan from users where id = ?", String.class, userId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int activeItems(UUID userId) {
        return count("select count(*) from items where user_id = ? and archived = false", userId);
    }

    private int locationCount(UUID userId) {
        return count("""
                select count(*) from locations l join spaces s on s.id = l.space_id where s.user_id = ?
                """, userId);
    }

    private int historyCount(UUID userId) {
        return count("""
                select count(*) from item_location_history h join items i on i.id = h.item_id
                where i.user_id = ?
                """, userId);
    }

    private ResponseEntity<JsonNode> createItem(String token, UUID locationId, String name) {
        return post(token, ITEMS, new CreateItemRequest(name, null, null, locationId), JsonNode.class);
    }

    private ResponseEntity<JsonNode> createSpaceRaw(String token, String name) {
        return post(token, "/api/v1/spaces", new CreateSpaceRequest(name, null, SpaceType.OFFICE), JsonNode.class);
    }

    private ResponseEntity<JsonNode> remember(String token, String message, UUID pinnedLocationId) {
        return post(token, REMEMBER, new RememberRequest(message, null, pinnedLocationId), JsonNode.class);
    }

    /** The one assistant_messages row of the account, newest first. */
    private Map<String, Object> lastAssistantRow(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select outcome, error_code, item_id, space_id, message
                from assistant_messages where user_id = ? order by created_at desc, id desc
                """, userId);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private void assertPlanLimitRefusal(ResponseEntity<JsonNode> response, String expectedInMessage) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("PLAN_LIMIT_REACHED");
        assertThat(response.getBody().get("message").asText()).contains(expectedInMessage);
    }

    // ------------------------------------------------------------------------------- the grant

    @Test
    void aFreshAccountIsFreeBecauseTheMigrationGrantsNothing() {
        String token = registerAndGetToken();

        // V9 adds the column with DEFAULT 'FREE' and no UPDATE: nothing is grandfathered, and a new
        // registration gets no head start either. UNLIMITED only ever arrives by operator grant.
        assertThat(planOf(subjectOf(token))).isEqualTo("FREE");
    }

    // ------------------------------------------------------------------------------ spaces (1)

    @Test
    void aSecondSpaceIsRefusedOnTheFreePlanAndAllowedAfterTheGrant() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        createSpace(token, "Home", SpaceType.HOME);

        assertPlanLimitRefusal(createSpaceRaw(token, "Office"), "1 space");
        assertThat(count("select count(*) from spaces where user_id = ?", userId)).isEqualTo(1);

        grantUnlimited(userId);

        assertThat(createSpaceRaw(token, "Office").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count("select count(*) from spaces where user_id = ?", userId)).isEqualTo(2);
    }

    @Test
    void reusingTheNameOfTheOnlySpaceIsStillADuplicateRatherThanAPlanLimit() {
        String token = registerAndGetToken();
        createSpace(token, "Home", SpaceType.HOME);

        // Both guards would refuse; the more specific one has to win, or the user is told to
        // subscribe when the space they are asking for already exists.
        ResponseEntity<JsonNode> duplicate = post(token, "/api/v1/spaces",
                new CreateSpaceRequest("  home ", null, SpaceType.HOME), JsonNode.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().get("code").asText()).isEqualTo("DUPLICATE_NAME");
    }

    // ----------------------------------------------------------------------- active items (35)

    @Test
    void theThirtyFifthItemIsCreatedAndTheThirtySixthIsRefused() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        UUID drawer = drawerOf(token);
        seedActiveItems(userId, drawer, 34);

        assertThat(createItem(token, drawer, "Item 35").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(activeItems(userId)).isEqualTo(35);

        assertPlanLimitRefusal(createItem(token, drawer, "Item 36"), "35 active items");
        assertThat(activeItems(userId)).isEqualTo(35);
    }

    @Test
    void archivingAnItemAtTheLimitFreesRoomForAnotherOne() {
        String token = releasedAccountAtTheItemLimit();
        UUID userId = subjectOf(token);
        UUID drawer = onlyLocationOf(userId);
        UUID archivable = jdbc.queryForObject(
                "select id from items where user_id = ? order by created_at desc limit 1", UUID.class, userId);

        assertPlanLimitRefusal(createItem(token, drawer, "One too many"), "35 active items");

        ResponseEntity<ItemResponse> archived = rest.exchange(ITEMS + "/" + archivable, HttpMethod.PUT,
                new HttpEntity<>(new UpdateItemRequest("Archived item", null, null, true), bearer(token)),
                ItemResponse.class);
        assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(archived.getBody().archived()).isTrue();

        // Archiving is the door in the wall: the row still exists, it just stops counting.
        assertThat(createItem(token, drawer, "Room again").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(activeItems(userId)).isEqualTo(35);
        assertThat(count("select count(*) from items where user_id = ?", userId)).isEqualTo(36);
    }

    @Test
    void anUnlimitedAccountPassesBothWalls() {
        String token = releasedAccountAtTheItemLimit();
        UUID userId = subjectOf(token);
        grantUnlimited(userId);

        assertThat(createItem(token, onlyLocationOf(userId), "Item 36").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(createSpaceRaw(token, "Office").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(activeItems(userId)).isEqualTo(36);
    }

    // ------------------------------------------------------------- both assistant remember paths

    /**
     * The chain path. The guard lives inside {@code ItemService.createAt}, which runs inside
     * {@code PlacementExecutor}'s transaction, so the refusal rolls the whole placement back —
     * including the locations {@code resolveOrCreateChain} had just created. The provenance row is
     * written afterwards in its own REQUIRES_NEW transaction and survives that rollback, which is
     * also the first end-to-end proof of the FAILED outcome across a real transaction boundary.
     */
    @Test
    void theAssistantChainPathIsRefusedAtTheLimitAndLeavesNoPartialRowBehind() {
        String token = releasedAccountAtTheItemLimit();
        UUID userId = subjectOf(token);
        UUID spaceId = jdbc.queryForObject("select id from spaces where user_id = ?", UUID.class, userId);
        int locationsBefore = locationCount(userId);
        int historyBefore = historyCount(userId);

        assertPlanLimitRefusal(remember(token, PASSPORT_SENTENCE, null), "35 active items");

        assertThat(activeItems(userId)).isEqualTo(35);
        assertThat(count("select count(*) from items where user_id = ? and name = 'Passport'", userId)).isZero();
        // Bedroom > Wardrobe > Top Drawer were created and rolled back with the item, and so was
        // the open history record the item would have opened.
        assertThat(locationCount(userId)).isEqualTo(locationsBefore);
        assertThat(historyCount(userId)).isEqualTo(historyBefore);
        Map<String, Object> row = lastAssistantRow(userId);
        assertThat(row)
                .containsEntry("outcome", "FAILED")
                .containsEntry("error_code", "PLAN_LIMIT_REACHED")
                .containsEntry("message", PASSPORT_SENTENCE);
        assertThat(row.get("item_id")).isNull();
        // The space was resolved before the executor ran, so that half of the diagnosis is kept.
        assertThat(row.get("space_id")).isEqualTo(spaceId);
    }

    /** The pinned path (BR-7): no model runs, so the refusal is the only thing that can happen. */
    @Test
    void theAssistantPinnedPathIsRefusedAtTheLimitAndRecordsFailedWithNoSpace() {
        String token = releasedAccountAtTheItemLimit();
        UUID userId = subjectOf(token);
        int locationsBefore = locationCount(userId);

        assertPlanLimitRefusal(remember(token, "kabel 20A", onlyLocationOf(userId)), "35 active items");

        assertThat(activeItems(userId)).isEqualTo(35);
        assertThat(count("select count(*) from items where user_id = ? and name = 'kabel 20A'", userId)).isZero();
        assertThat(locationCount(userId)).isEqualTo(locationsBefore);
        Map<String, Object> row = lastAssistantRow(userId);
        assertThat(row)
                .containsEntry("outcome", "FAILED")
                .containsEntry("error_code", "PLAN_LIMIT_REACHED")
                .containsEntry("message", "kabel 20A");
        assertThat(row.get("item_id")).isNull();
        // No space link: on the pinned path only the executor's own lookup knows the space, and the
        // refusal happens after it inside the same rolled-back transaction.
        assertThat(row.get("space_id")).isNull();
    }

    // ------------------------------------------------------------------------------- fixtures

    /** A FREE account with one space, one drawer, and exactly 35 active items in it. */
    private String releasedAccountAtTheItemLimit() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        UUID drawer = drawerOf(token);
        seedActiveItems(userId, drawer, 34);
        assertThat(createItem(token, drawer, "Item 35").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(activeItems(userId)).isEqualTo(35);
        return token;
    }

    private UUID drawerOf(String token) {
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse drawer = createLocation(token, home.id(), "Drawer", LocationType.DRAWER, null);
        return drawer.id();
    }

    private UUID onlyLocationOf(UUID userId) {
        return jdbc.queryForObject("""
                select l.id from locations l join spaces s on s.id = l.space_id where s.user_id = ?
                """, UUID.class, userId);
    }
}
