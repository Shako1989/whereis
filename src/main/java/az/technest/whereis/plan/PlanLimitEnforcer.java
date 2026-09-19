package az.technest.whereis.plan;

import az.technest.whereis.item.ItemRepository;
import az.technest.whereis.space.SpaceRepository;
import az.technest.whereis.user.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The one place that decides whether an account may create another space or another item. Three
 * creation paths call it — {@code SpaceService.create}, {@code ItemService.createAt} (which
 * {@code ItemService.create} and both of {@code PlacementExecutor}'s paths go through) — so the
 * rule exists once and cannot drift between the REST API and the assistant.
 *
 * <p>Counting is done with {@code count} queries, never by loading rows, and both counts are
 * userId-scoped as §6 requires. Only ACTIVE items count: archiving frees room, which is what makes
 * the item limit a wall the user can get past rather than a dead end.
 *
 * <p>Locations are deliberately NOT limited — the product rule names spaces and items only.
 *
 * <p>Nothing here holds a transaction of its own: every caller is already inside one, so the count
 * and the insert that follows it share a connection and a snapshot. Two concurrent creations can
 * still both pass the check and leave the account one over the limit; that is accepted (the
 * alternative is a lock per creation, for a wall whose exact position does not matter), and the
 * next creation is refused.
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
        if (spaceRepository.countByUserId(userId) >= freeTier.spaces()) {
            throw PlanLimitReachedException.spaces(freeTier.spaces());
        }
    }

    /** @throws PlanLimitReachedException 409 PLAN_LIMIT_REACHED when the account has no active item left */
    public void requireRoomForAnotherItem(UUID userId) {
        if (hasUnlimitedEntitlement(userId)) {
            return;
        }
        if (itemRepository.countByUserIdAndArchivedFalse(userId) >= freeTier.items()) {
            throw PlanLimitReachedException.activeItems(freeTier.items());
        }
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
}
