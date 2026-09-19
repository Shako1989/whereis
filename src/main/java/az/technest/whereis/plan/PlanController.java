package az.technest.whereis.plan;

import az.technest.whereis.common.security.CurrentUser;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own plan. Lives in {@code plan/} with the rule it describes (package by feature),
 * while the path sits under {@code /users/me} with the other endpoints about the caller's account.
 *
 * <p>There is no path parameter and no query parameter: the subject comes from the JWT, so there is
 * nothing to ask about another user and no ownership check to get wrong.
 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class PlanController {

    private final PlanLimitEnforcer planLimits;

    /**
     * What the caller is entitled to, what the free tier allows, and how much of it is used.
     * Costs three statements — one plan lookup and the two {@code count}s the guard itself runs.
     */
    @GetMapping("/plan")
    public PlanStatusResponse myPlan() {
        return planLimits.statusOf(CurrentUser.id());
    }
}
