package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.item.dto.UpdateItemRequest;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.SubscriptionState;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.CreateSpaceRequest;
import az.technest.whereis.space.dto.SpaceResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
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
        // The numbers come from whereis.plans.free.* — the client must never hardcode them.
        assertThat(status.limits().spaces()).isEqualTo(1);
        assertThat(status.limits().items()).isEqualTo(35);
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
        assertThat(status.usage().spaces()).isEqualTo(status.limits().spaces().longValue());
    }

    @Test
    void atTheItemLimitTheEndpointSaysFullAndTheNextCreationIsRefused() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        UUID drawer = drawerOf(token);
        seedActiveItems(userId, drawer, 34);
        assertThat(createItem(token, drawer, "Item 35").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertPlanLimitRefusal(createItem(token, drawer, "Item 36"));

        PlanStatusResponse status = planOf(token);
        assertThat(status.usage().activeItems()).isEqualTo(35L);
        assertThat(status.usage().activeItems()).isEqualTo(status.limits().items().longValue());
    }

    // ---------------------------------------------------------------------- the UNLIMITED grant

    @Test
    void anUnlimitedAccountReportsAPresentLimitsObjectWithBothMembersNull() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        createItem(token, drawerOf(token), "Passport");
        grantUnlimited(userId);
        assertThat(createSpaceRaw(token, "Office").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> raw = get(token, PLAN, JsonNode.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody().get("plan").asText()).isEqualTo("UNLIMITED");
        // THE SUPERSEDED ASSERTION. BR-11 shipped `limits: null` for a granted account and this
        // test pinned it; the four-tier ladder makes that unrepresentable, because MAX is "ten
        // spaces, unlimited items" and a whole-object null cannot say that. So `limits` is now
        // always an OBJECT and a null is exactly one thing — no ceiling on that allowance.
        assertThat(raw.getBody().has("limits")).isTrue();
        assertThat(raw.getBody().get("limits").isNull()).isFalse();
        assertThat(raw.getBody().get("limits").get("spaces").isNull()).isTrue();
        assertThat(raw.getBody().get("limits").get("items").isNull()).isTrue();
        assertThat(raw.getBody().get("source").asText()).isEqualTo("GRANT");
        assertThat(raw.getBody().get("subscription").isNull()).isTrue();
        assertThat(raw.getBody().get("usage").get("spaces").asLong()).isEqualTo(2L);
        assertThat(raw.getBody().get("usage").get("activeItems").asLong()).isEqualTo(1L);
    }

    // --------------------------------------------------------------------------- the paid ladder

    @Test
    void aStandardSubscriberReportsStandardsLimitsAndTheSubscriptionItself() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        createItem(token, drawerOf(token), "Passport");
        seedSubscription(userId, Plan.STANDARD, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)));

        ResponseEntity<JsonNode> raw = get(token, PLAN, JsonNode.class);

        assertThat(raw.getBody().get("plan").asText()).isEqualTo("STANDARD");
        assertThat(raw.getBody().get("limits").get("spaces").asInt()).isEqualTo(3);
        assertThat(raw.getBody().get("limits").get("items").asInt()).isEqualTo(100);
        assertThat(raw.getBody().get("source").asText()).isEqualTo("SUBSCRIPTION");
        assertThat(raw.getBody().get("subscription").get("productId").asText())
                .isEqualTo("whereis_standard_annual");
        assertThat(raw.getBody().get("subscription").get("tier").asText()).isEqualTo("STANDARD");
        assertThat(raw.getBody().get("subscription").get("acknowledged").asBoolean()).isTrue();
    }

    @Test
    void maxReportsAFiniteSpaceCeilingBesideANullItemCeiling() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        seedSubscription(userId, Plan.MAX, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)));

        ResponseEntity<JsonNode> raw = get(token, PLAN, JsonNode.class);

        assertThat(raw.getBody().get("plan").asText()).isEqualTo("MAX");
        assertThat(raw.getBody().get("limits").get("spaces").asInt()).isEqualTo(10);
        assertThat(raw.getBody().get("limits").get("items").isNull()).isTrue();
    }

    @Test
    void anOperatorGrantBeatsALowerSubscriptionAndTheSubscriptionIsStillReported() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        grantTier(userId, Plan.PRO);
        seedSubscription(userId, Plan.STANDARD, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)));

        ResponseEntity<JsonNode> raw = get(token, PLAN, JsonNode.class);

        // max(), not an override — and the paid half is still reported so it stays manageable.
        assertThat(raw.getBody().get("plan").asText()).isEqualTo("PRO");
        assertThat(raw.getBody().get("source").asText()).isEqualTo("GRANT");
        assertThat(raw.getBody().get("subscription").get("tier").asText()).isEqualTo("STANDARD");
    }

    @Test
    void anOverLimitAccountAfterADowngradeReportsUsageAboveItsLimitsWithoutClamping() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        grantTier(userId, Plan.PRO);
        createSpace(token, "Home", SpaceType.HOME);
        createSpace(token, "Office", SpaceType.OFFICE);
        createSpace(token, "Car", SpaceType.CAR);
        createSpace(token, "Garage", SpaceType.GARAGE);
        createSpace(token, "Warehouse", SpaceType.WAREHOUSE);
        grantTier(userId, Plan.FREE);
        seedSubscription(userId, Plan.STANDARD, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)));

        PlanStatusResponse status = planOf(token);

        assertThat(status.plan()).isEqualTo(Plan.STANDARD);
        assertThat(status.limits().spaces()).isEqualTo(3);
        // No clamping and no error: the honest body is the one that explains the 409 on the next
        // POST, and the shipped client's progress bar already coerces the fraction to 0..1.
        assertThat(status.usage().spaces()).isEqualTo(5L);
        assertPlanLimitRefusal(createSpaceRaw(token, "Attic"));
    }

    // ------------------------------------------------------------------- the ladder endpoint

    @Test
    void theLadderEndpointListsFourTiersInOrderWithTheirProductIdsAndNeverTheOperatorGrant() {
        String token = registerAndGetToken();

        ResponseEntity<JsonNode> ladder = get(token, "/api/v1/plans", JsonNode.class);

        assertThat(ladder.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ladder.getBody().isArray()).isTrue();
        assertThat(ladder.getBody()).hasSize(4);
        assertThat(ladder.getBody().get(0).get("tier").asText()).isEqualTo("FREE");
        assertThat(ladder.getBody().get(0).get("productId").isNull()).isTrue();
        assertThat(ladder.getBody().get(1).get("tier").asText()).isEqualTo("STANDARD");
        assertThat(ladder.getBody().get(1).get("productId").asText()).isEqualTo("whereis_standard_annual");
        assertThat(ladder.getBody().get(2).get("tier").asText()).isEqualTo("PRO");
        assertThat(ladder.getBody().get(3).get("tier").asText()).isEqualTo("MAX");
        assertThat(ladder.getBody().get(3).get("limits").get("spaces").asInt()).isEqualTo(10);
        assertThat(ladder.getBody().get(3).get("limits").get("items").isNull()).isTrue();
        // UNLIMITED is never listed: it is not purchasable, and a client that saw it would render
        // it as something to buy.
        assertThat(ladder.getBody().toString()).doesNotContain("UNLIMITED");
    }

    @Test
    void theLadderEndpointIsUnreachableWithoutAToken() {
        assertThat(rest.getForEntity("/api/v1/plans", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------------------ the ceiling

    @Test
    void theEndpointIsUnreachableWithoutAToken() {
        ResponseEntity<JsonNode> anonymous = rest.getForEntity(PLAN, JsonNode.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
