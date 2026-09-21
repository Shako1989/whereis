package az.technest.whereis.plan;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What every tier on the ladder allows, and which Play product buys it. Bound from
 * {@code whereis.plans.*} — this replaces {@code FreeTierLimits}, which could only describe one
 * tier.
 *
 * <p><strong>Configuration, not a {@code plans} table.</strong> The limits of a tier NOBODY IS ON
 * must be known, because the ladder screen renders all four; data derived from rows cannot answer
 * that, and a table of tiers would be configuration wearing a schema. The guard also runs on every
 * creation, where config is a field read and a table is a join, and every tuning would otherwise
 * be a migration that two environments could disagree about. The one fact that genuinely IS data —
 * "which tier did this purchase buy" — is stored on {@code user_subscriptions.tier}, resolved once
 * at verification time, so re-pointing configuration later never re-tiers an existing purchase.
 *
 * <p><strong>What a config change can and cannot do.</strong> Re-pointing an existing tier's
 * product id, or retuning its numbers, is an environment change with no backend release. Adding a
 * NEW tier is not: a tier is a {@link Plan} constant pinned by {@code ck_users_plan} and
 * {@code ck_user_subscriptions_tier}, so it needs a code change, a {@code V<n>} widening both
 * CHECKs, and a {@code PlanTest} / {@code SubscriptionTierTest} update. A tier also cannot be
 * REMOVED from this catalog — the constructor requires every {@link Plan} constant to be
 * configured. Retiring one means keeping the constant (so stored rows still read) and dropping it
 * from {@link #purchasable()} / {@link #ladder()}; deleting the constant while rows reference it is
 * a data migration, not a config change.
 *
 * <p><strong>{@code null} means NO CEILING ON THAT ALLOWANCE, per allowance.</strong> That is the
 * only way {@code MAX} ("10 spaces, unlimited items") is expressible.
 * {@code Integer.MAX_VALUE} was rejected — it is a number, so it reaches the wire ("143 of
 * 2147483647"), it compares silently, and nothing stops arithmetic on it. A {@code boolean
 * unlimitedItems} beside an {@code int} was rejected for the reason already recorded on
 * {@code PlanStatusResponse}: it states one fact twice and lets the pair contradict itself.
 *
 * <p><strong>Why the prefix is {@code whereis} and not {@code whereis.plans}.</strong> A
 * constructor-bound record binds each COMPONENT under the prefix, so a component named
 * {@code plans} under {@code whereis} is what makes {@code whereis.plans.free.items} — and
 * therefore {@code WHEREIS_PLANS_FREE_ITEMS}, the name the deployment runbook uses — the binding
 * key. Declaring the prefix as {@code whereis.plans} would have bound
 * {@code whereis.plans.tiers.free.items} instead. {@code whereis.legal.*} and {@code whereis.play.*}
 * are simply ignored here, exactly as {@code ai.claude.*} is ignored by {@code AiProperties}.
 *
 * @param plans every {@link Plan} constant, each with its three ceilings and its product id
 */
@ConfigurationProperties("whereis")
public record PlanCatalog(Map<Plan, TierConfig> plans) {

    /**
     * One row of the ladder.
     *
     * @param spaces    maximum spaces, or {@code null} for no ceiling on spaces
     * @param items     maximum ACTIVE items, or {@code null} for no ceiling on items
     * @param listings  maximum simultaneously ACTIVE marketplace listings, or {@code null} for no
     *                  ceiling. MAX has an unbounded {@code items} and a FINITE {@code listings},
     *                  and that asymmetry is the clearest demonstration of why a ceiling is
     *                  nullable PER ALLOWANCE rather than per tier: an unbounded PUBLIC surface
     *                  per account is a spam vector in a way that a private inventory is not.
     * @param productId the Play product that buys this tier; {@code null} for FREE and UNLIMITED
     */
    public record TierConfig(Integer spaces, Integer items, Integer listings, String productId) {
    }

    public PlanCatalog {
        // Built defensively rather than with new EnumMap<>(plans): that constructor throws
        // "Specified map is empty" for an empty non-enum map, which would mask the message below
        // for a `whereis.plans:` block that is present but empty.
        EnumMap<Plan, TierConfig> copy = new EnumMap<>(Plan.class);
        if (plans != null) {
            plans.forEach((tier, config) -> {
                if (tier != null && config != null) {
                    copy.put(tier, config);
                }
            });
        }
        // UNLIMITED is the operator grant, and there is nothing about it to configure: both
        // allowances are unbounded by definition and it is not for sale. It is therefore SUPPLIED
        // here rather than required in the file — which is also the only workable answer, because
        // a YAML entry whose every value is blank binds to nothing at all and is indistinguishable
        // from an absent one. Writing it with a ceiling anyway is still refused, below.
        copy.putIfAbsent(Plan.UNLIMITED, new TierConfig(null, null, null, null));
        for (Plan tier : Plan.values()) {
            if (!copy.containsKey(tier)) {
                throw new IllegalStateException("whereis.plans." + key(tier) + " is not configured");
            }
        }
        for (Plan tier : Plan.values()) {
            TierConfig config = copy.get(tier);
            requireUsable(tier, "spaces", config.spaces());
            requireUsable(tier, "items", config.items());
            requireUsable(tier, "listings", config.listings());
            boolean hasProduct = config.productId() != null && !config.productId().isBlank();
            if (tier.isPurchasable() && !hasProduct) {
                throw new IllegalStateException(
                        "whereis.plans." + key(tier) + ".product-id must be set: " + tier + " is purchasable");
            }
            if (!tier.isPurchasable() && hasProduct) {
                throw new IllegalStateException("whereis.plans." + key(tier)
                        + ".product-id must NOT be set: " + tier + " is not purchasable");
            }
        }
        TierConfig unlimited = copy.get(Plan.UNLIMITED);
        if (unlimited.spaces() != null || unlimited.items() != null || unlimited.listings() != null) {
            throw new IllegalStateException("whereis.plans.unlimited must leave spaces, items and "
                    + "listings all blank (no ceiling)");
        }
        requireMonotonic(copy, "spaces", TierConfig::spaces);
        requireMonotonic(copy, "items", TierConfig::items);
        requireMonotonic(copy, "listings", TierConfig::listings);
        requireDistinctProductIds(copy);
        plans = Collections.unmodifiableMap(copy);
    }

    /** The configuration of one tier. Never null — the constructor proved every constant is present. */
    public TierConfig of(Plan tier) {
        return plans.get(tier);
    }

    /** Maximum spaces for this tier, or {@code null} for no ceiling on spaces. */
    public Integer spaceLimit(Plan tier) {
        return of(tier).spaces();
    }

    /** Maximum ACTIVE items for this tier, or {@code null} for no ceiling on items. */
    public Integer itemLimit(Plan tier) {
        return of(tier).items();
    }

    /** Maximum simultaneously ACTIVE listings for this tier, or {@code null} for no ceiling. */
    public Integer listingLimit(Plan tier) {
        return of(tier).listings();
    }

    /**
     * The tier a Play product id buys. Never throws and never guesses: an unknown product id is
     * {@link Optional#empty()}, which the verify endpoint answers with 400 PLAY_PRODUCT_UNKNOWN
     * before any Google call. It is never defaulted to FREE and never to the lowest paid tier.
     */
    public Optional<Plan> tierOf(String productId) {
        if (productId == null || productId.isBlank()) {
            return Optional.empty();
        }
        return plans.entrySet().stream()
                .filter(entry -> productId.equals(entry.getValue().productId()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** STANDARD, PRO, MAX in ladder order. */
    public List<Plan> purchasable() {
        return Arrays.stream(Plan.values()).filter(Plan::isPurchasable).toList();
    }

    /**
     * FREE, STANDARD, PRO, MAX in ladder order — the body of {@code GET /api/v1/plans}. UNLIMITED
     * is deliberately absent: it is not purchasable, and listing it would invite a client to render
     * it as an option.
     */
    public List<Plan> ladder() {
        return Arrays.stream(Plan.values()).filter(tier -> tier != Plan.UNLIMITED).toList();
    }

    /**
     * Whether some purchasable tier above {@code tier} actually raises the SPACES allowance. This
     * is what decides whether a 409 may invite an upgrade: at MAX (10 spaces, the top of the paid
     * ladder) it is false, and advertising a purchase that does not exist is a Play policy exposure
     * as well as a lie.
     */
    public boolean aHigherTierRaisesSpaces(Plan tier) {
        return raises(tier, TierConfig::spaces);
    }

    /** Whether some purchasable tier above {@code tier} actually raises the ITEMS allowance. */
    public boolean aHigherTierRaisesItems(Plan tier) {
        return raises(tier, TierConfig::items);
    }

    /** Whether some purchasable tier above {@code tier} actually raises the LISTINGS allowance. */
    public boolean aHigherTierRaisesListings(Plan tier) {
        return raises(tier, TierConfig::listings);
    }

    private boolean raises(Plan tier, Function<TierConfig, Integer> allowance) {
        Integer mine = allowance.apply(of(tier));
        if (mine == null) {
            return false;
        }
        return purchasable().stream()
                .filter(higher -> higher.compareTo(tier) > 0)
                .anyMatch(higher -> {
                    Integer theirs = allowance.apply(of(higher));
                    return theirs == null || theirs > mine;
                });
    }

    private static void requireUsable(Plan tier, String allowance, Integer value) {
        if (value != null && value < 1) {
            throw new IllegalStateException("whereis.plans." + key(tier) + "." + allowance
                    + " must be at least 1 or blank (no ceiling), was " + value);
        }
    }

    /**
     * The ladder must be monotonically non-decreasing: a higher tier may never allow less, with
     * {@code null} treated as +infinity. A hard failure rather than a warning, because a ladder
     * that goes down is a product bug the screen would render as an upgrade that takes something
     * away — but the message names BOTH offending tiers and the rule, because the one legitimate
     * reason to raise a lower tier (the pre-promotion escape hatch in deploy/README.md Step 10)
     * requires raising every tier above it too, and an operator pasting a number at 2am needs to be
     * told that rather than left with a crash-looping container.
     */
    private static void requireMonotonic(Map<Plan, TierConfig> tiers, String allowance,
                                         Function<TierConfig, Integer> value) {
        Plan[] ladder = Plan.values();
        for (int i = 1; i < ladder.length; i++) {
            Plan lower = ladder[i - 1];
            Plan higher = ladder[i];
            Integer below = value.apply(tiers.get(lower));
            Integer above = value.apply(tiers.get(higher));
            if (below == null) {
                // The lower tier already has no ceiling; the higher one must not have a finite one.
                if (above != null) {
                    throw new IllegalStateException("whereis.plans." + key(lower) + "." + allowance
                            + " (no ceiling) exceeds whereis.plans." + key(higher) + "." + allowance
                            + " (" + above + "); a higher tier may never allow less"
                            + " — raise every tier above it too");
                }
                continue;
            }
            if (above != null && above < below) {
                throw new IllegalStateException("whereis.plans." + key(lower) + "." + allowance
                        + " (" + below + ") exceeds whereis.plans." + key(higher) + "." + allowance
                        + " (" + above + "); a higher tier may never allow less"
                        + " — raise every tier above it too");
            }
        }
    }

    private static void requireDistinctProductIds(Map<Plan, TierConfig> tiers) {
        Map<String, Plan> seen = new java.util.HashMap<>();
        tiers.forEach((tier, config) -> {
            String productId = config.productId();
            if (productId == null) {
                return;
            }
            Plan clash = seen.putIfAbsent(productId, tier);
            if (clash != null) {
                throw new IllegalStateException("whereis.plans." + key(tier) + ".product-id '" + productId
                        + "' is already used by whereis.plans." + key(clash)
                        + "; one product id maps to exactly one tier");
            }
        });
    }

    private static String key(Plan tier) {
        return tier.name().toLowerCase(Locale.ROOT);
    }
}
