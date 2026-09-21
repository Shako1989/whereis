package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.plan.dto.PlanLimitsResponse;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.plan.dto.PlanSubscriptionResponse;
import az.technest.whereis.plan.dto.PlanUsageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The wire shape of {@code GET /api/v1/users/me/plan} (and of the 200 from
 * {@code POST /users/me/plan/purchases}, which is the same body), pinned offline. The plan screen
 * branches on it, so a field name or a null that changes silently is a client bug shipped by the
 * backend.
 */
class PlanStatusResponseTest {

    private final ObjectMapper json = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String serialize(PlanStatusResponse response) throws JsonProcessingException {
        return json.writeValueAsString(response);
    }

    @Test
    void aFreeAccountSerializesItsLimitsItsUsageAndNoSubscription() throws JsonProcessingException {
        String body = serialize(new PlanStatusResponse(Plan.FREE, new PlanLimitsResponse(1, 100, 1),
                new PlanUsageResponse(1L, 19L, 0), EntitlementSource.NONE, null));

        assertThat(json.readTree(body)).hasToString(
                "{\"plan\":\"FREE\",\"limits\":{\"spaces\":1,\"items\":100,\"listings\":1},"
                        + "\"usage\":{\"spaces\":1,\"activeItems\":19,\"activeListings\":0},"
                        + "\"source\":\"NONE\",\"subscription\":null}");
    }

    @Test
    void maxSerializesAFiniteSpaceCeilingBesideANullItemCeiling() throws JsonProcessingException {
        // THE SHAPE THE WHOLE CHANGE EXISTS FOR. A whole-object null could not say "ten spaces,
        // unlimited items" at all, which is why nullability moved to the two members and why
        // BR-11's "limits is null for UNLIMITED" is superseded.
        String body = serialize(new PlanStatusResponse(Plan.MAX, new PlanLimitsResponse(10, null, 25),
                new PlanUsageResponse(4L, 1203L, 0), EntitlementSource.SUBSCRIPTION,
                new PlanSubscriptionResponse("whereis_max_annual", Plan.MAX, SubscriptionState.ACTIVE,
                        Instant.parse("2027-09-19T10:04:00Z"), PurchaseProvenance.PLAY_PURCHASE, true,
                        true, null, null)));

        assertThat(json.readTree(body).get("limits").get("spaces").asInt()).isEqualTo(10);
        assertThat(json.readTree(body).get("limits").get("items").isNull()).isTrue();
        assertThat(json.readTree(body).get("limits").has("items")).isTrue();
        assertThat(json.readTree(body).get("subscription").get("productId").asText())
                .isEqualTo("whereis_max_annual");
        assertThat(json.readTree(body).get("subscription").get("entitledUntil").asText())
                .isEqualTo("2027-09-19T10:04:00Z");
        assertThat(json.readTree(body).get("subscription").get("acknowledged").asBoolean()).isTrue();
    }

    @Test
    void anOperatorGrantSerializesBothCeilingsAsNullAndNoSubscription() throws JsonProcessingException {
        String body = serialize(new PlanStatusResponse(Plan.UNLIMITED, new PlanLimitsResponse(null, null, null),
                new PlanUsageResponse(4L, 19L, 0), EntitlementSource.GRANT, null));

        // `limits` itself is now ALWAYS an object; a null is exactly one thing, "no ceiling on that
        // allowance". Two representations of "everything is unlimited" would let the wire
        // contradict itself, which is the defect the original DTO javadoc rejected.
        assertThat(json.readTree(body).get("limits").isNull()).isFalse();
        assertThat(json.readTree(body).get("limits").get("spaces").isNull()).isTrue();
        assertThat(json.readTree(body).get("limits").get("items").isNull()).isTrue();
        assertThat(json.readTree(body).get("source").asText()).isEqualTo("GRANT");
        assertThat(json.readTree(body).get("subscription").isNull()).isTrue();
        // Usage is never null: a granted account still renders "4 spaces, 19 items".
        assertThat(json.readTree(body).get("usage").get("activeItems").asLong()).isEqualTo(19L);
    }

    @Test
    void anOverLimitAccountReportsUsageAboveItsLimitsWithoutClamping() throws JsonProcessingException {
        // A PRO account that dropped to STANDARD keeps its five spaces. The honest body is the one
        // that says so; clamping would hide the only fact that explains the 409 on the next POST.
        String body = serialize(new PlanStatusResponse(Plan.STANDARD, new PlanLimitsResponse(3, 300, 3),
                new PlanUsageResponse(5L, 412L, 0), EntitlementSource.SUBSCRIPTION,
                new PlanSubscriptionResponse("whereis_standard_annual", Plan.STANDARD,
                        SubscriptionState.ACTIVE, Instant.parse("2027-01-01T00:00:00Z"),
                        PurchaseProvenance.PLAY_PURCHASE, true, true, null, null)));

        assertThat(json.readTree(body).get("usage").get("spaces").asLong()).isEqualTo(5L);
        assertThat(json.readTree(body).get("limits").get("spaces").asInt()).isEqualTo(3);
    }
}
