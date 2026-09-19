package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.item.dto.UpdateItemRequest;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.CreateSpaceRequest;
import az.technest.whereis.space.dto.SpaceResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /api/v1/users/me/plan} against a real database — the upgrade screen's only honest
 * source of "what plan am I on and how much of it have I used".
 *
 * <p>The point of these is agreement rather than arithmetic: every number the endpoint reports is
 * cross-checked against the wall that actually refuses the next creation, because a screen that
 * says "0 of 1 spaces used" over a 409 is worse than no screen. Like {@code PlanLimitIT} they run
 * with the REAL production limits — no {@code @TestPropertySource}, which would fork the shared
 * Spring context.
 */
class PlanStatusIT extends AbstractIntegrationTest {

    private static final String PLAN = "/api/v1/users/me/plan";
    private static final String ITEMS = "/api/v1/items";

    private PlanStatusResponse planOf(String token) {
        ResponseEntity<PlanStatusResponse> response = get(token, PLAN, PlanStatusResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> createItem(String token, UUID locationId, String name) {
        return post(token, ITEMS, new CreateItemRequest(name, null, null, locationId), JsonNode.class);
    }

    private ResponseEntity<JsonNode> createSpaceRaw(String token, String name) {
        return post(token, "/api/v1/spaces", new CreateSpaceRequest(name, null, SpaceType.OFFICE), JsonNode.class);
    }

    /** One space with one drawer in it; returns the drawer. */
    private UUID drawerOf(String token) {
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        return createLocation(token, home.id(), "Drawer", LocationType.DRAWER, null).id();
    }

    private void assertPlanLimitRefusal(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("PLAN_LIMIT_REACHED");
    }

    // --------------------------------------------------------------------------- the free plan

    @Test
    void aFreeAccountReportsItsPlanItsLimitsAndItsTrueUsage() {
        String token = registerAndGetToken();
        UUID drawer = drawerOf(token);
        createItem(token, drawer, "Passport");
        createItem(token, drawer, "Keys");
        createItem(token, drawer, "Charger");

        PlanStatusResponse status = planOf(token);

        assertThat(status.plan()).isEqualTo(Plan.FREE);
        // The numbers come from whereis.limits.free.* — the client must never hardcode them.
        assertThat(status.limits().spaces()).isEqualTo(1);
        assertThat(status.limits().items()).isEqualTo(100);
        assertThat(status.usage().spaces()).isEqualTo(1L);
        assertThat(status.usage().activeItems()).isEqualTo(3L);
    }

    @Test
    void aFreshAccountReportsZeroUsageRatherThanAnEmptyBody() {
        PlanStatusResponse status = planOf(registerAndGetToken());

        assertThat(status.plan()).isEqualTo(Plan.FREE);
        assertThat(status.usage().spaces()).isZero();
        assertThat(status.usage().activeItems()).isZero();
    }

    @Test
    void archivedItemsStopCountingTowardsUsageWhileStillExisting() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        UUID drawer = drawerOf(token);
        createItem(token, drawer, "Passport");
        UUID archivable = UUID.fromString(createItem(token, drawer, "Winter coat").getBody().get("id").asText());

        assertThat(planOf(token).usage().activeItems()).isEqualTo(2L);

        ResponseEntity<ItemResponse> archived = rest.exchange(ITEMS + "/" + archivable, HttpMethod.PUT,
                new HttpEntity<>(new UpdateItemRequest("Winter coat", null, null, true), bearer(token)),
                ItemResponse.class);
        assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Exactly what the guard counts: the row is still there, it just stopped taking room.
        assertThat(planOf(token).usage().activeItems()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from items where user_id = ?", Integer.class, userId))
                .isEqualTo(2);
    }

    @Test
    void usageCountsOnlyTheCallersOwnSpacesAndItems() {
        String mine = registerAndGetToken();
        createItem(mine, drawerOf(mine), "Passport");
        String theirs = registerAndGetToken();
        UUID theirDrawer = drawerOf(theirs);
        createItem(theirs, theirDrawer, "Their passport");
        createItem(theirs, theirDrawer, "Their keys");

        // No path parameter exists, so there is nothing to ask about another user — and the counts
        // behind the answer are userId-scoped like every other finder here.
        assertThat(planOf(mine).usage().activeItems()).isEqualTo(1L);
        assertThat(planOf(theirs).usage().activeItems()).isEqualTo(2L);
        assertThat(planOf(mine).usage().spaces()).isEqualTo(1L);
    }

    // ------------------------------------------------------- agreement with the guard (the wall)

    @Test
    void atTheSpaceLimitTheEndpointSaysFullAndTheNextCreationIsRefused() {
        String token = registerAndGetToken();
        createSpace(token, "Home", SpaceType.HOME);

        assertPlanLimitRefusal(createSpaceRaw(token, "Office"));

        PlanStatusResponse status = planOf(token);
        assertThat(status.usage().spaces()).isEqualTo(status.limits().spaces());
    }

    @Test
    void atTheItemLimitTheEndpointSaysFullAndTheNextCreationIsRefused() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        UUID drawer = drawerOf(token);
        seedActiveItems(userId, drawer, 99);
        assertThat(createItem(token, drawer, "Item 100").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertPlanLimitRefusal(createItem(token, drawer, "Item 101"));

        PlanStatusResponse status = planOf(token);
        assertThat(status.usage().activeItems()).isEqualTo(100L);
        assertThat(status.usage().activeItems()).isEqualTo(status.limits().items());
    }

    // ---------------------------------------------------------------------- the UNLIMITED grant

    @Test
    void anUnlimitedAccountReportsAPresentNullLimitsAndKeepsReportingUsage() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        createItem(token, drawerOf(token), "Passport");
        grantUnlimited(userId);
        assertThat(createSpaceRaw(token, "Office").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> raw = get(token, PLAN, JsonNode.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody().get("plan").asText()).isEqualTo("UNLIMITED");
        // The decision the client branches on, asserted on the wire: the key is PRESENT and null,
        // not omitted and not a set of numbers nobody enforces.
        assertThat(raw.getBody().has("limits")).isTrue();
        assertThat(raw.getBody().get("limits").isNull()).isTrue();
        assertThat(raw.getBody().get("usage").get("spaces").asLong()).isEqualTo(2L);
        assertThat(raw.getBody().get("usage").get("activeItems").asLong()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------------------ the ceiling

    @Test
    void theEndpointIsUnreachableWithoutAToken() {
        ResponseEntity<JsonNode> anonymous = rest.getForEntity(PLAN, JsonNode.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
