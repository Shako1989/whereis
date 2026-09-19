package az.technest.whereis.plan;

import az.technest.whereis.item.ItemRepository;
import az.technest.whereis.plan.dto.PlanLimitsResponse;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.plan.dto.PlanUsageResponse;
import az.technest.whereis.space.SpaceRepository;
import az.technest.whereis.user.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that decides whether an account may create another space or another item, and the
 * one place that reports that decision to the client. Three creation paths call it —
 * {@code SpaceService.create}, {@code ItemService.createAt} (which {@code ItemService.create} and
 * both of {@code PlacementExecutor}'s paths go through) — so the rule exists once and cannot drift
 * between the REST API and the assistant. {@link #statusOf(UUID)} answers
 * {@code GET /users/me/plan} from the SAME counts and the SAME configuration, for the same reason:
 * a usage number computed anywhere else would eventually disagree with the wall it describes.
 *
 * <p>Counting is done with {@code count} queries, never by loading rows, and both counts are
 * userId-scoped as §6 requires. Only ACTIVE items count: archiving frees room, which is what makes
 * the item limit a wall the user can get past rather than a dead end.
 *
 * <p>Locations are deliberately NOT limited — the product rule names spaces and items only.
 *
 * <p>Neither GUARD holds a transaction of its own (only the read side does): every caller is already
 * inside one, so the count and the insert that follows it share a connection and a snapshot. Two
 * concurrent creations can still both pass the check and leave the account one over the limit; that
 * is accepted (the alternative is a lock per creation, for a wall whose exact position does not
 * matter), and the next creation is refused.
 */
@Service
@RequiredArgsConstructor
public class PlanLimitEnforcer {

    private final UserRepository userRepository;
    private final SpaceRepository spaceRepository;
    private final ItemRepository itemRepository;
    private final FreeTierLimits freeTier;

    /** @throws PlanLimitReachedException 409 PLAN_LIMIT_REACHED when the account has no space left */
    public void requireRoomForAnotherSpace(UUID userId) {
        if (hasUnlimitedEntitlement(userId)) {
            return;
        }
        if (countSpaces(userId) >= freeTier.spaces()) {
            throw PlanLimitReachedException.spaces(freeTier.spaces());
        }
    }

    /** @throws PlanLimitReachedException 409 PLAN_LIMIT_REACHED when the account has no active item left */
    public void requireRoomForAnotherItem(UUID userId) {
        if (hasUnlimitedEntitlement(userId)) {
            return;
        }
        if (countActiveItems(userId) >= freeTier.items()) {
            throw PlanLimitReachedException.activeItems(freeTier.items());
        }
    }

    /**
     * What this account is entitled to, what a FREE account may hold, and how much of it exists —
     * the read side of the rule the two {@code require*} methods enforce, for
     * {@code GET /api/v1/users/me/plan}.
     *
     * <p>Nothing is re-derived here. The plan comes from {@link #hasUnlimitedEntitlement}, the
     * ceilings from the same {@link FreeTierLimits} the guards compare against, and the usage from
     * {@link #countSpaces} / {@link #countActiveItems} — the very expressions the guards run. So
     * "1 of 1 spaces used" and "409 PLAN_LIMIT_REACHED" are two readings of one set of numbers, and
     * a change to what "active" means moves both at once.
     *
     * <p>Null limits mean no limit applies, which is the same fact the guards state by returning
     * before they count anything. Usage is reported on BOTH plans and therefore costs two counts
     * even for an UNLIMITED account: the guard skips them because it has nothing to compare them
     * with, while the screen still shows "19 items".
     *
     * <p>Read-only and transactional, unlike everything else on this class: the three statements are
     * a report rather than a guard, and one snapshot is what stops it from answering with a space
     * count taken before a concurrent creation and an item count taken after it.
     */
    @Transactional(readOnly = true)
    public PlanStatusResponse statusOf(UUID userId) {
        boolean unlimited = hasUnlimitedEntitlement(userId);
        return new PlanStatusResponse(
                unlimited ? Plan.UNLIMITED : Plan.FREE,
                unlimited ? null : new PlanLimitsResponse(freeTier.spaces(), freeTier.items()),
                new PlanUsageResponse(countSpaces(userId), countActiveItems(userId)));
    }

    /**
     * Whether this account is entitled to unlimited use. <strong>The body of this method is meant
     * to grow; the column behind it is not.</strong>
     *
     * <p>Today it is exactly {@code users.plan = 'UNLIMITED'}, a grant an operator makes by hand.
     * When billing lands the rule becomes {@code plan = 'UNLIMITED' OR an active subscription}, and
     * the subscription half MUST live in its own state — a Play RTDN reporting an expiry writes
     * "no longer subscribed" somewhere, and if that somewhere were this column it would silently
     * erase a hand-made grant for an internal user or a tester. So {@code users.plan} is written
     * only by the V9 migration's default or by an operator's UPDATE, never by billing. The entity
     * field has no setter for the same reason.
     *
     * <p>An account that no longer exists reads as {@link Plan#FREE}: the limits then apply to a
     * request whose insert is about to fail on the {@code users} foreign key anyway, and answering
     * "unlimited" to an unknown subject is the wrong default for a guard.
     */
    public boolean hasUnlimitedEntitlement(UUID userId) {
        return userRepository.findPlanById(userId).orElse(Plan.FREE) == Plan.UNLIMITED;
    }

    /** The spaces this account holds. One expression, shared by the guard and the report. */
    private long countSpaces(UUID userId) {
        return spaceRepository.countByUserId(userId);
    }

    /**
     * The ACTIVE items this account holds — archived rows are excluded, which is what makes
     * archiving free room. One expression, shared by the guard and the report: a second count
     * written separately is exactly how the upgrade screen would start lying.
     */
    private long countActiveItems(UUID userId) {
        return itemRepository.countByUserIdAndArchivedFalse(userId);
    }
}
