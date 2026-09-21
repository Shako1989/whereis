package az.technest.whereis.plan;

import az.technest.whereis.item.ItemRepository;
import az.technest.whereis.marketplace.ListingRepository;
import az.technest.whereis.marketplace.ListingStatus;
import az.technest.whereis.plan.dto.PlanLimitsResponse;
import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.plan.dto.PlanSubscriptionResponse;
import az.technest.whereis.plan.dto.PlanUsageResponse;
import az.technest.whereis.space.SpaceRepository;
import az.technest.whereis.user.UserRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that decides what an account is entitled to, whether it may create another space
 * or another item, and how that decision is reported. Three creation paths call it —
 * {@code SpaceService.create} and {@code ItemService.createAt} (which {@code ItemService.create}
 * and both of {@code PlacementExecutor}'s paths go through) — so the rule exists once and cannot
 * drift between the REST API and the assistant. {@link #statusOf(UUID)} answers
 * {@code GET /users/me/plan} from the SAME entitlement call, the SAME catalog and the SAME counts.
 *
 * <p><strong>THE ENTITLEMENT IS A max(), NOT AN OVERRIDE.</strong>
 * {@code effectiveTier = max(users.plan, best entitling subscription)} over the ladder
 * FREE &lt; STANDARD &lt; PRO &lt; MAX &lt; UNLIMITED. An operator grant therefore always wins,
 * and a subscription can never downgrade a granted account — which is the whole reason billing
 * does not write {@code users.plan}, and why revoking a grant leaves a paying subscriber on their
 * paid tier.
 *
 * <p><strong>LIMITS GATE CREATION AND NOTHING ELSE.</strong> An account that drops below what it
 * already holds keeps every space, location, item, photo and history record, and may still read,
 * rename, move, archive, unarchive and delete them. The invariant is a property of the call graph,
 * not of a runtime check: {@code requireRoom*} has exactly two callers, and
 * {@code OwnershipScopingArchTest#onlyTheTwoCreationPathsConsultThePlan} makes a third one a build
 * failure.
 *
 * <p>Counting is done with {@code count} queries, never by loading rows, and both counts are
 * userId-scoped as §6 requires. Only ACTIVE items count: archiving frees room.
 *
 * <p>Locations are deliberately NOT limited — the product rule names spaces and items only.
 *
 * <p>Neither GUARD holds a transaction of its own (only the read side does): every caller is
 * already inside one. Two concurrent creations can still both pass the check and leave the account
 * one over the limit; that is accepted, and the next creation is refused. The cost of the guard
 * grew from one statement to two (the plan lookup plus the entitling-subscription lookup) before it
 * counts anything; it is the same two the report shares.
 */
@Service
@RequiredArgsConstructor
public class PlanLimitEnforcer {

    private final UserRepository userRepository;
    private final SpaceRepository spaceRepository;
    private final ItemRepository itemRepository;
    // marketplace -> plan (the service calls the guard) and plan -> marketplace (the guard counts
    // rows) is the EXISTING plan <-> item shape verbatim: the guard depends on the REPOSITORY, the
    // service on the GUARD, so there is no bean cycle.
    private final ListingRepository listingRepository;
    private final UserSubscriptionRepository subscriptionRepository;
    private final PlanCatalog catalog;

    /** @throws PlanLimitReachedException 409 PLAN_LIMIT_REACHED when the account has no space left */
    public void requireRoomForAnotherSpace(UUID userId) {
        Plan tier = effectiveTierOf(userId);
        Integer cap = catalog.spaceLimit(tier);
        if (cap == null) {
            // No ceiling on SPACES for this tier — not necessarily an unlimited account. MAX has
            // unlimited items and exactly ten spaces, which is why the check is per allowance.
            return;
        }
        if (countSpaces(userId) >= cap) {
            throw PlanLimitReachedException.spaces(cap, tier, catalog.aHigherTierRaisesSpaces(tier));
        }
    }

    /** @throws PlanLimitReachedException 409 PLAN_LIMIT_REACHED when the account has no active item left */
    public void requireRoomForAnotherItem(UUID userId) {
        Plan tier = effectiveTierOf(userId);
        Integer cap = catalog.itemLimit(tier);
        if (cap == null) {
            return;
        }
        if (countActiveItems(userId) >= cap) {
            throw PlanLimitReachedException.activeItems(cap, tier, catalog.aHigherTierRaisesItems(tier));
        }
    }

    /**
     * @throws PlanLimitReachedException 409 PLAN_LIMIT_REACHED when the account has no listing left
     */
    public void requireRoomForAnotherListing(UUID userId) {
        Plan tier = effectiveTierOf(userId);
        Integer cap = catalog.listingLimit(tier);
        if (cap == null) {
            return;
        }
        if (countActiveListings(userId) >= cap) {
            throw PlanLimitReachedException.activeListings(
                    cap, tier, catalog.aHigherTierRaisesListings(tier));
        }
    }

    /**
     * What this account is entitled to RIGHT NOW: the higher of the operator grant on
     * {@code users.plan} and the best entitling subscription. <strong>This is the ONE method that
     * decides</strong>, and {@link #statusOf(UUID)} reaches the same answer through the same
     * private overload rather than re-deriving it — a report and a wall that compute the rule
     * separately disagree the first time the rule changes.
     *
     * <p>An account that no longer exists reads as {@link Plan#FREE}: the limits then apply to a
     * request whose insert is about to fail on the {@code users} foreign key anyway, and answering
     * "unlimited" to an unknown subject is the wrong default for a guard.
     */
    public Plan effectiveTierOf(UUID userId) {
        Instant now = Instant.now();
        return effectiveTierOf(grantedTierOf(userId), tierOf(best(subscriptionRepository.entitlingOf(userId, now))));
    }

    /**
     * What this account is entitled to, what that tier allows, how much of it exists, and which
     * side of the max() decided it — the read side of the rule the two {@code require*} methods
     * enforce, for {@code GET /api/v1/users/me/plan} and for the purchase endpoint's 200.
     *
     * <p>Nothing is re-derived here. The tier comes from the same {@link #effectiveTierOf(Plan, Plan)}
     * the guards use, the ceilings from the same {@link PlanCatalog}, and the usage from
     * {@link #countSpaces} / {@link #countActiveItems} — the very expressions the guards run. So
     * "5 of 5 spaces used" and "409 PLAN_LIMIT_REACHED" are two readings of one set of numbers.
     *
     * <p>Usage is reported on EVERY tier and is never clamped: {@code usage.spaces = 5} against
     * {@code limits.spaces = 3} is the honest body for an account that dropped from PRO to
     * STANDARD, and clamping it would hide the only fact that explains the refusal.
     *
     * <p>Read-only and transactional, unlike everything else on this class: the four statements are
     * a report rather than a guard, and one snapshot is what stops it from answering with a space
     * count taken before a concurrent creation and an item count taken after it.
     */
    @Transactional(readOnly = true)
    public PlanStatusResponse statusOf(UUID userId) {
        Instant now = Instant.now();
        Plan granted = grantedTierOf(userId);
        UserSubscription best = best(subscriptionRepository.entitlingOf(userId, now));
        Plan subscribed = tierOf(best);
        Plan effective = effectiveTierOf(granted, subscribed);
        UserSubscription reported = reportable(best, userId, now);
        return new PlanStatusResponse(
                effective,
                new PlanLimitsResponse(catalog.spaceLimit(effective), catalog.itemLimit(effective),
                        catalog.listingLimit(effective)),
                new PlanUsageResponse(countSpaces(userId), countActiveItems(userId),
                        countActiveListings(userId)),
                sourceOf(granted, subscribed),
                reported == null ? null : PlanSubscriptionResponse.of(reported, catalog, now));
    }

    /**
     * WHICH SUBSCRIPTION THE REPORT NAMES — a strictly wider question than which one entitles, and
     * the ONE place the two are allowed to differ.
     *
     * <p>{@code plan}, {@code limits}, {@code usage} and {@code source} above are computed from
     * {@code entitlingOf} alone and this method cannot influence any of them; a fifth statement was
     * added here rather than widening the entitling finder precisely so that stays true.
     * {@code PlanLimitEnforcerTest} pins it: an entitling STANDARD row beside a live ON_HOLD MAX
     * row must badge STANDARD.
     *
     * <p>The reduction is <strong>entitling first, then tier, then expiry, then id</strong>. The
     * entitling row wins outright when there is one, because the badge and the strip agreeing is
     * worth more than naming the largest purchase; only when NOTHING entitles does the report fall
     * back to the best live row, which is exactly the ON_HOLD / PAUSED / PENDING case the field was
     * widened for. The last three legs make the answer deterministic — an account with two rows of
     * the same tier must not have the reported product id flip between requests.
     *
     * <p>The extra statement is skipped entirely for the common case: an entitling row is always
     * manageable ({@code manageableOf}'s predicate is strictly weaker), so when one exists there is
     * nothing to look up.
     */
    private UserSubscription reportable(UserSubscription entitling, UUID userId, Instant now) {
        if (entitling != null) {
            return entitling;
        }
        return best(subscriptionRepository.manageableOf(userId));
    }

    /**
     * THE max() RULE, written once. Both public entry points call this and nothing else combines a
     * grant with a subscription; {@code PlanLimitEnforcerTest#theGuardAndTheReportAgreeOnEveryTier}
     * pins that they agree.
     */
    private Plan effectiveTierOf(Plan granted, Plan subscribed) {
        return Plan.higherOf(granted, subscribed);
    }

    /**
     * Which side won. SUBSCRIPTION takes the tie, because a real paid subscription must always be
     * manageable; a grant that merely matches it should not hide the "manage subscription" copy.
     */
    private EntitlementSource sourceOf(Plan granted, Plan subscribed) {
        if (effectiveTierOf(granted, subscribed) == Plan.FREE) {
            return EntitlementSource.NONE;
        }
        return subscribed.isAtLeast(granted) && subscribed != Plan.FREE
                ? EntitlementSource.SUBSCRIPTION
                : EntitlementSource.GRANT;
    }

    private Plan grantedTierOf(UUID userId) {
        return userRepository.findPlanById(userId).orElse(Plan.FREE);
    }

    private static Plan tierOf(UserSubscription best) {
        return best == null ? Plan.FREE : best.getTier();
    }

    /**
     * The best entitling row: highest tier first, then the one that lasts longest, then the
     * greatest id. The tie-break is specified rather than left to the database's row order so that
     * the {@code subscription} the report names is deterministic — an account with two entitling
     * rows of the same tier must not have the reported product id flip between requests.
     */
    private static UserSubscription best(List<UserSubscription> entitling) {
        return entitling.stream()
                .max(Comparator.comparing(UserSubscription::getTier)
                        .thenComparing(UserSubscription::getEntitledUntil)
                        .thenComparing(UserSubscription::getId))
                .orElse(null);
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

    /**
     * The LIVE listings this account holds. SOLD and WITHDRAWN free room, exactly as archiving
     * does for items — and a HIDDEN listing still counts, because freeing the cap on a hide would
     * let a seller re-publish what an operator just took down.
     */
    private long countActiveListings(UUID userId) {
        return listingRepository.countByUserIdAndStatus(userId, ListingStatus.ACTIVE);
    }
}
