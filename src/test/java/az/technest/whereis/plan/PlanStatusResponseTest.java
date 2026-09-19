package az.technest.whereis.plan;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.plan.dto.PlanLimitsResponse;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.plan.dto.PlanUsageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The wire shape of {@code GET /api/v1/users/me/plan}, pinned offline. The upgrade screen branches
 * on it, so a field name or a null that changes silently is a client bug shipped by the backend.
 */
class PlanStatusResponseTest {

    private final ObjectMapper json = new ObjectMapper();

    private String serialize(PlanStatusResponse response) throws JsonProcessingException {
        return json.writeValueAsString(response);
    }

    @Test
    void aFreeAccountSerializesItsLimitsAndItsUsage() throws JsonProcessingException {
        String body = serialize(new PlanStatusResponse(Plan.FREE, new PlanLimitsResponse(1, 100),
                new PlanUsageResponse(1L, 19L)));

        assertThat(json.readTree(body)).hasToString(
                "{\"plan\":\"FREE\",\"limits\":{\"spaces\":1,\"items\":100},"
                        + "\"usage\":{\"spaces\":1,\"activeItems\":19}}");
    }

    @Test
    void anUnlimitedAccountSerializesLimitsAsAnExplicitNullRatherThanOmittingIt() throws JsonProcessingException {
        String body = serialize(new PlanStatusResponse(Plan.UNLIMITED, null, new PlanUsageResponse(4L, 19L)));

        // The key is PRESENT and null. That is the decision the client branches on: one condition
        // (limits == null) instead of two ("absent or null"), and it holds for any decoder rather
        // than only for those that map a missing key to null.
        assertThat(body).contains("\"limits\":null");
        assertThat(json.readTree(body).has("limits")).isTrue();
        assertThat(json.readTree(body).get("limits").isNull()).isTrue();
        // Usage is never null: an unlimited account still renders "4 spaces, 19 items".
        assertThat(json.readTree(body).get("usage").get("activeItems").asLong()).isEqualTo(19L);
    }
}
