package az.technest.whereis.plan;

import az.technest.whereis.common.security.CurrentUser;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.plan.dto.PurchaseVerificationRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own plan, and the purchases that raise it. Lives in {@code plan/} with the rule it
 * describes (package by feature), while the paths sit under {@code /users/me} with the other
 * endpoints about the caller's account.
 *
 * <p>There is no path parameter and no query parameter on either route: the subject comes from the
 * JWT, so there is nothing to ask about another user and no ownership check to get wrong.
 */
@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class PlanController {

    private final PlanLimitEnforcer planLimits;
    private final PurchaseVerificationService purchases;

    /**
     * What the caller is entitled to, what that tier allows, how much of it is used, which side of
     * {@code max(grant, subscription)} decided it, and the subscription itself when there is one.
     * Costs four statements — one plan lookup, one entitling-subscription lookup, and the two
     * {@code count}s the guard itself runs.
     */
    @GetMapping("/plan")
    public PlanStatusResponse myPlan() {
        return planLimits.statusOf(CurrentUser.id());
    }

    /**
     * Links a Play purchase to the caller's account.
     *
     * <p>A POST because it creates the link between an account and a purchase, and idempotent by
     * the unique constraint on the purchase token rather than by an idempotency header.
     *
     * <p>Answers <strong>200 with the full {@link PlanStatusResponse}</strong>, byte-identical to
     * {@code GET /users/me/plan}. Not 201 + Location: there is no addressable purchase resource,
     * and the client's next action is always "render the plan", so returning it makes it impossible
     * for the purchase result and the plan screen to disagree. The client must ADOPT this body
     * rather than issue a second GET.
     */
    @PostMapping("/plan/purchases")
    public PlanStatusResponse verifyPurchase(@Valid @RequestBody PurchaseVerificationRequest request) {
        return purchases.verify(CurrentUser.id(), request);
    }
}
