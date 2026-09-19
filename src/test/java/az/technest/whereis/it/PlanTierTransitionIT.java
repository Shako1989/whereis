package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.assistant.dto.RememberRequest;
import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.item.dto.MoveItemRequest;
import az.technest.whereis.item.dto.UpdateItemRequest;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.plan.Plan;
import az.technest.whereis.plan.SubscriptionState;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.SpaceResponse;
import az.technest.whereis.space.dto.UpdateSpaceRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * <strong>NOTHING EXISTING IS EVER TAKEN AWAY.</strong> Limits gate CREATION and nothing else, and
 * that has to hold across every transition on the ladder — an expiry, a cancellation, a refund, a
 * pause, an operator revoking a grant, or an operator lowering a tier's configured numbers.
 *
 * <p>An account that drops below what it already holds keeps every space, location, item and
 * history record, and may still read, rename, move, archive, unarchive and delete them. Only
 * {@code POST /spaces}, {@code POST /items} and {@code POST /assistant/remember} may answer 409.
 *
 * <p>These run with the REAL production limits — no {@code @TestPropertySource}, which would fork
 * the shared Spring context.
 */
class PlanTierTransitionIT extends AbstractIntegrationTest {

    private static final String ITEMS = "/api/v1/items";
    private static final String SPACES = "/api/v1/spaces";
    private static final String PLAN = "/api/v1/users/me/plan";
    private static final String REMEMBER = "/api/v1/assistant/remember";

    private ResponseEntity<JsonNode> createSpaceRaw(String token, String name, SpaceType type) {
        return post(token, SPACES, new az.technest.whereis.space.dto.CreateSpaceRequest(name, null, type),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> createItemRaw(String token, UUID locationId, String name) {
        return post(token, ITEMS, new CreateItemRequest(name, null, null, locationId), JsonNode.class);
    }

    private PlanStatusResponse planOf(String token) {
        return get(token, PLAN, PlanStatusResponse.class).getBody();
    }

    private void expire(UUID subscriptionId) {
        jdbc.update("update user_subscriptions set entitled_until = now() - interval '1 day' where id = ?",
                subscriptionId);
    }

    private static void assertPlanLimitRefusal(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code").asText()).isEqualTo("PLAN_LIMIT_REACHED");
    }

    // ----------------------------------------------------------------- spaces, after an expiry

    @Test
    void anExpiredProSubscriptionKeepsAllFiveSpacesUsableAndRefusesOnlyTheSixth() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        UUID subscription = seedSubscription(userId, Plan.PRO, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)));
        SpaceResponse first = createSpace(token, "Home", SpaceType.HOME);
        createSpace(token, "Office", SpaceType.OFFICE);
        createSpace(token, "Car", SpaceType.CAR);
        createSpace(token, "Garage", SpaceType.GARAGE);
        SpaceResponse fifth = createSpace(token, "Warehouse", SpaceType.WAREHOUSE);
        assertThat(planOf(token).plan()).isEqualTo(Plan.PRO);

        // No write anywhere, by anyone: the entitled_until predicate simply stops matching. This is
        // also the fail-closed guard — even if every RTDN is lost, entitlement lapses on its own.
        expire(subscription);

        assertThat(planOf(token).plan()).isEqualTo(Plan.FREE);
        // EVERYTHING that is not a creation still works on all five spaces.
        assertThat(get(token, SPACES + "/" + fifth.id(), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange(SPACES + "/" + fifth.id(), HttpMethod.PUT,
                new HttpEntity<>(new UpdateSpaceRequest("Warehouse renamed", null, SpaceType.WAREHOUSE),
                        bearer(token)), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        LocationResponse shelf = createLocation(token, fifth.id(), "Shelf", LocationType.SHELF, null);
        // Locations are never limited, and items are under their own (separate) cap.
        ResponseEntity<JsonNode> item = createItemRaw(token, shelf.id(), "Drill");
        assertThat(item.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID itemId = UUID.fromString(item.getBody().get("id").asText());
        LocationResponse elsewhere = createLocation(token, first.id(), "Drawer", LocationType.DRAWER, null);
        assertThat(post(token, ITEMS + "/" + itemId + "/move",
                new MoveItemRequest(elsewhere.id(), null), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange(ITEMS + "/" + itemId, HttpMethod.PUT,
                new HttpEntity<>(new UpdateItemRequest("Drill", null, null, true), bearer(token)),
                ItemResponse.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Deleting is untouched too, all the way down: the location and then the space itself.
        assertThat(rest.exchange("/api/v1/locations/" + shelf.id(), HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange(SPACES + "/" + fifth.id(), HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // And the ONE thing that is refused — still four spaces against a ceiling of one.
        assertPlanLimitRefusal(createSpaceRaw(token, "Attic", SpaceType.OTHER));
    }

    // ------------------------------------------------------------------ items, after a downgrade

    @Test
    void aDowngradeFromProToStandardKeepsEveryItemEditableAndRefusesOnlyTheNextOne() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        UUID pro = seedSubscription(userId, Plan.PRO, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)));
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        UUID drawer = createLocation(token, home.id(), "Drawer", LocationType.DRAWER, null).id();
        seedActiveItems(userId, drawer, 400);
        UUID keeper = UUID.fromString(createItemRaw(token, drawer, "Passport").getBody().get("id").asText());
        assertThat(planOf(token).usage().activeItems()).isEqualTo(401L);

        // PRO (600) expires; a STANDARD subscription (300) remains. The account is 101 over.
        expire(pro);
        seedSubscription(userId, Plan.STANDARD, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)));

        PlanStatusResponse status = planOf(token);
        assertThat(status.plan()).isEqualTo(Plan.STANDARD);
        assertThat(status.limits().items()).isEqualTo(300);
        assertThat(status.usage().activeItems()).isEqualTo(401L);   // reported, never clamped

        // Archive, unarchive, edit, move, delete: all still allowed.
        assertThat(rest.exchange(ITEMS + "/" + keeper, HttpMethod.PUT,
                new HttpEntity<>(new UpdateItemRequest("Passport", null, null, true), bearer(token)),
                ItemResponse.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // UNARCHIVING IS DELIBERATELY UNGUARDED. It raises activeItems without going through
        // createAt, so an account over its cap can go one higher. That is the correct reading of
        // "nothing existing is ever taken away": the item exists, the user owns it, and refusing
        // would make an item they own permanently unrestorable. Pinned so nobody "fixes" it.
        assertThat(rest.exchange(ITEMS + "/" + keeper, HttpMethod.PUT,
                new HttpEntity<>(new UpdateItemRequest("Passport", null, null, false), bearer(token)),
                ItemResponse.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(planOf(token).usage().activeItems()).isEqualTo(401L);
        assertThat(rest.exchange(ITEMS + "/" + keeper, HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Only creation is refused.
        assertPlanLimitRefusal(createItemRaw(token, drawer, "One more"));
    }

    @Test
    void anAssistantRefusalRecordsFailedAndCreatesNoLocations() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        seedSubscription(userId, Plan.STANDARD, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)));
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        UUID drawer = createLocation(token, home.id(), "Drawer", LocationType.DRAWER, null).id();
        seedActiveItems(userId, drawer, 300);
        int locationsBefore = jdbc.queryForObject(
                "select count(*) from locations l join spaces s on s.id = l.space_id where s.user_id = ?",
                Integer.class, userId);

        ResponseEntity<JsonNode> refused = post(token, REMEMBER,
                new RememberRequest("I put my passport in the bedroom wardrobe top drawer", null, null),
                JsonNode.class);

        assertPlanLimitRefusal(refused);
        // The V9 behaviour must survive the tier change: the row records the refusal, the chain the
        // resolver had just created rolls back with the transaction, and the item never exists.
        assertThat(jdbc.queryForObject(
                "select count(*) from assistant_messages where user_id = ? and outcome = 'FAILED'"
                        + " and error_code = 'PLAN_LIMIT_REACHED'", Integer.class, userId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from locations l join spaces s on s.id = l.space_id where s.user_id = ?",
                Integer.class, userId))
                .isEqualTo(locationsBefore);
    }

    // ------------------------------------------------- the other ways a tier can stop entitling

    @Test
    void everyNonEntitlingRowShapeDropsTheTierWhileLeavingEveryExistingRowReadable() {
        record Shape(String name, SubscriptionState state, boolean voided, boolean superseded) {
        }
        List<Shape> shapes = List.of(
                new Shape("paused", SubscriptionState.PAUSED, false, false),
                new Shape("on hold", SubscriptionState.ON_HOLD, false, false),
                new Shape("expired state", SubscriptionState.EXPIRED, false, false),
                new Shape("unknown state", SubscriptionState.UNKNOWN, false, false),
                new Shape("voided", SubscriptionState.ACTIVE, true, false),
                new Shape("superseded", SubscriptionState.ACTIVE, false, true));

        for (Shape shape : shapes) {
            String token = registerAndGetToken();
            UUID userId = subjectOf(token);
            SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
            UUID drawer = createLocation(token, home.id(), "Drawer", LocationType.DRAWER, null).id();
            UUID item = UUID.fromString(createItemRaw(token, drawer, "Passport").getBody().get("id").asText());
            UUID replacement = shape.superseded()
                    ? seedSubscription(userId, Plan.PRO, SubscriptionState.EXPIRED,
                            Instant.now().minus(Duration.ofDays(1)))
                    : null;
            seedSubscription(userId, Plan.PRO, shape.state(),
                    Instant.now().plus(Duration.ofDays(365)),
                    shape.voided() ? Instant.now().minus(Duration.ofHours(1)) : null,
                    replacement, "seed-" + UUID.randomUUID());

            // PAUSED and ON_HOLD keep a FUTURE expiry on purpose: the state predicate is what
            // excludes them. A refund revokes immediately rather than at expiry. The replaced half
            // of an upgrade stops counting.
            assertThat(planOf(token).plan()).as(shape.name()).isEqualTo(Plan.FREE);
            assertThat(get(token, ITEMS + "/" + item, JsonNode.class).getStatusCode())
                    .as(shape.name()).isEqualTo(HttpStatus.OK);
            assertPlanLimitRefusal(createSpaceRaw(token, "Office", SpaceType.OFFICE));
        }
    }

    @Test
    void aCanceledSubscriptionWithAFutureTermStillEntitles() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        seedSubscription(userId, Plan.PRO, SubscriptionState.CANCELED,
                Instant.now().plus(Duration.ofDays(30)));

        // Auto-renew off is not "over": the user paid for the term and keeps it.
        assertThat(planOf(token).plan()).isEqualTo(Plan.PRO);
        assertThat(createSpaceRaw(token, "Home", SpaceType.HOME).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void revokingAnOperatorGrantLeavesAPayingSubscriberOnTheirPaidTier() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        grantTier(userId, Plan.UNLIMITED);
        seedSubscription(userId, Plan.STANDARD, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)));
        assertThat(planOf(token).plan()).isEqualTo(Plan.UNLIMITED);

        grantTier(userId, Plan.FREE);

        // Only the column changed; the subscription half is untouched. This is the entire reason
        // the entitlement is a max() and not an override.
        assertThat(planOf(token).plan()).isEqualTo(Plan.STANDARD);
    }

    @Test
    void anOperatorGrantOfProSurvivesAnExpiredStandardSubscription() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        grantTier(userId, Plan.PRO);
        UUID standard = seedSubscription(userId, Plan.STANDARD, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)));

        expire(standard);

        PlanStatusResponse status = planOf(token);
        assertThat(status.plan()).isEqualTo(Plan.PRO);
        assertThat(status.limits().spaces()).isEqualTo(5);
        // WAVE 2 WIDENED THIS, deliberately. It used to assert subscription == null, because the
        // report named the best ENTITLING row and this one has run out. It now names the best LIVE
        // row (UserSubscriptionRepository#manageableOf), and a row whose state Google still reports
        // as ACTIVE is live: its expiry notification may simply have been lost, in which case
        // Google is still auto-renewing it and the user needs the Manage button more than ever.
        //
        // What must NOT change, and is asserted here: the badge still comes from the grant alone,
        // and `entitling` is how the client tells "you keep Pro until…" from "Pro is paused".
        assertThat(status.subscription()).isNotNull();
        assertThat(status.subscription().tier()).isEqualTo(Plan.STANDARD);
        assertThat(status.subscription().entitling()).isFalse();
    }

    @Test
    void whileBothHalvesEntitleTheUserGetsTheHigherOfTheTwo() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        // A downgrade purchase produces a second row before the first is superseded; max() gives
        // the generous answer and needs no code.
        seedSubscription(userId, Plan.PRO, SubscriptionState.ACTIVE, Instant.now().plus(Duration.ofDays(20)));
        seedSubscription(userId, Plan.STANDARD, SubscriptionState.ACTIVE,
                Instant.now().plus(Duration.ofDays(365)));

        assertThat(planOf(token).plan()).isEqualTo(Plan.PRO);
    }
}
