package az.technest.whereis.plan;

import az.technest.whereis.plan.dto.PlanStatusResponse;
import az.technest.whereis.plan.dto.PurchaseVerificationRequest;
import az.technest.whereis.plan.play.PlayAccountHash;
import az.technest.whereis.plan.play.PlayLineItem;
import az.technest.whereis.plan.play.PlayApiException;
import az.technest.whereis.plan.play.PlayBillingNotConfiguredException;
import az.technest.whereis.plan.play.PlayProductMismatchException;
import az.technest.whereis.plan.play.PlayProductUnknownException;
import az.technest.whereis.plan.play.PlayPurchaseInvalidException;
import az.technest.whereis.plan.play.PlayProperties;
import az.technest.whereis.plan.play.PlayPurchaseNotActiveException;
import az.technest.whereis.plan.play.PlaySubscription;
import az.technest.whereis.plan.play.PlaySubscriptionsApi;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Links a Play purchase to the calling account: verify with Google, record what Google said, and
 * acknowledge. The whole of {@code POST /api/v1/users/me/plan/purchases}.
 *
 * <p><strong>NO {@code @Transactional} ANYWHERE ON THIS CLASS</strong>, the same shape as
 * {@code AssistantService}. Every database step is a method on {@link SubscriptionWriter} reached
 * through the Spring proxy, so each is its own transaction and the two Google calls sit strictly
 * between transactions. ArchUnit enforces that no transactional method calls the Play port.
 *
 * <p>IDEMPOTENCY IS A CONSTRAINT, NOT A CODE PATH. {@code ux_user_subscriptions_purchase_token} is
 * the key; a replayed POST — and the client WILL replay, because {@code queryPurchasesAsync} runs
 * on every foreground — either refreshes the caller's row or loses the insert race and is repaired
 * in a fresh transaction. There is no "have I seen this?" query whose answer can be stale and no
 * idempotency header to get wrong.
 *
 * <p>TOKENS ARE BEARER CREDENTIALS. Never logged above DEBUG (§6): every INFO line carries
 * {@link #digest} — twelve hex characters of SHA-256 — plus the userId, the product, the state and
 * the outcome.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PurchaseVerificationService {

    /**
     * How recently a token must have been verified for a repeat POST to be answered from the stored
     * row with NO Google call. Google enforces per-project daily and per-minute quotas that every
     * user shares, so this caps a looping or malicious client at one Google call per token per
     * minute. The client's own 24-hour re-post rule is the other half.
     */
    private static final Duration REVERIFY_WINDOW = Duration.ofSeconds(60);

    /**
     * Acknowledge attempts before giving up. THE INTERIM SUBSTITUTE FOR THE RECONCILER, which is
     * out of scope this wave: Google auto-refunds and revokes an unacknowledged purchase after 3
     * days — 5 MINUTES for a test purchase, which is every purchase on the closed track this wave
     * exists to serve. A single transient 5xx here would otherwise produce an account that this
     * database says is entitled for a year and that Google has silently refunded.
     */
    private static final int ACKNOWLEDGE_ATTEMPTS = 3;
    private static final long ACKNOWLEDGE_BACKOFF_MILLIS = 200L;

    private final UserSubscriptionRepository subscriptions;
    private final SubscriptionWriter writer;
    private final PlaySubscriptionsApi play;
    private final PlayProperties playProperties;
    private final PlanCatalog catalog;
    private final PlanLimitEnforcer planLimits;
    private final SubscriptionLinkResolver linkResolver;

    /**
     * The order of operations, with the transaction boundaries:
     * <ol>
     *   <li>[no tx] bean validation (the controller's {@code @Valid}), then <strong>billing must
     *       be configured at all</strong> — else 501 PLAY_BILLING_NOT_CONFIGURED, ahead of the
     *       product check and before any query. Ahead of everything on purpose: every step below
     *       answers a question about a PURCHASE, and with
     *       {@code whereis.play.provider=disabled} there is no purchase to have a question
     *       about.</li>
     *   <li>[no tx] the claimed product must be configured — else 400 PLAY_PRODUCT_UNKNOWN, with NO
     *       Google call.</li>
     *   <li>[tx, readOnly] look the token up. Another account's ⇒ 409 PLAN_PURCHASE_NOT_OWNED with
     *       no Google call. The caller's own and verified within the last minute ⇒ REPLAY THE
     *       STORED VERDICT: 200 if it entitles AND is acknowledged, 409 PLAY_PURCHASE_NOT_ACTIVE if
     *       it does not. An entitling row that is NOT acknowledged always falls through and
     *       re-verifies, because the acknowledgement is still owed and nothing else in this wave
     *       will ever retry it.</li>
     *   <li>[NO TRANSACTION — EXTERNAL] {@code subscriptionsv2.get}. 502 PLAY_UNAVAILABLE or 400
     *       PLAY_PURCHASE_INVALID, nothing written.</li>
     *   <li>[no tx] cross-checks, fail-closed, STATE FIRST: a non-entitling purchase is always 409
     *       (retryable, token kept), so a state problem can never be answered with a drop-the-token
     *       400. Then the tier is resolved from GOOGLE's line items, then the account cross-check.</li>
     *   <li>[tx] upsert by purchase token, with the race repaired in a fresh transaction.</li>
     *   <li>[NO TRANSACTION — EXTERNAL] acknowledge, with bounded retries.</li>
     *   <li>[tx, REQUIRES_NEW] record the acknowledgement.</li>
     *   <li>[tx, readOnly] the caller's full plan status ⇒ 200.</li>
     * </ol>
     */
    public PlanStatusResponse verify(UUID callerId, PurchaseVerificationRequest request) {
        // 1b. BILLING IS SWITCHED OFF IN THIS DEPLOYMENT. Refused here rather than left to
        // DisabledPlaySubscriptionsApi (which would also throw, four steps later) for two reasons
        // worth keeping:
        //   * the answer becomes the SAME 501 for every request shape. Reaching step 2 first would
        //     answer an unrecognised productId with 400 PLAY_PRODUCT_UNKNOWN — "drop the token" —
        //     when the truth is that this server cannot look at any token at all.
        //   * step 3's 60-second replay window could otherwise answer 200 with an entitling body,
        //     from a row a previously google-configured process wrote. Nothing would be GRANTED (it
        //     is a pure read of committed state), but a 200 on this endpoint is indistinguishable
        //     from a successful purchase, and "never implies a grant" is the property this mode is
        //     deployed on. Refusing first makes the whole flow zero statements and zero reads.
        if (!playProperties.billingConfigured()) {
            log.info("Refusing a purchase claim from user {}: Play Billing is not configured "
                    + "(whereis.play.provider={})", callerId, playProperties.provider());
            throw new PlayBillingNotConfiguredException("Play Billing is not configured in this "
                    + "deployment, so this purchase cannot be verified. Keep the purchase token: it "
                    + "will be accepted once billing is switched on. Nothing has been charged or "
                    + "granted by this request.");
        }

        String token = request.purchaseToken().trim();
        String claimedProductId = request.productId().trim();

        // 2. An unknown product id never reaches Google, and is never guessed into a tier.
        if (catalog.tierOf(claimedProductId).isEmpty()) {
            throw new PlayProductUnknownException("Unknown product '" + claimedProductId + "'");
        }

        Instant now = Instant.now();

        // 3. What we already know about this token.
        Optional<UserSubscription> known = subscriptions.findByPurchaseToken(token);
        if (known.isPresent()) {
            UserSubscription row = known.get();
            requireOwnedBy(row, callerId, token);
            boolean entitling = row.entitlesAt(now);
            boolean fresh = row.getVerifiedAt() != null
                    && row.getVerifiedAt().isAfter(now.minus(REVERIFY_WINDOW));
            if (fresh && entitling && row.isAcknowledged()) {
                log.info("Purchase {} for user {} replayed from cache, still {}",
                        digest(token), callerId, row.getState());
                return planLimits.statusOf(callerId);
            }
            if (fresh && !entitling) {
                // The verdict is a function of the purchase's state, not of how recently we were
                // asked. Answering 200 here would tell the client to stop polling a PENDING payment.
                throw notActive(row.getState());
            }
        }

        // 4. Google. Nothing has been written, and nothing will be if this throws.
        PlaySubscription google = play.get(token);
        Instant verifiedAt = Instant.now();

        // 5. Cross-checks. The tier comes from Google's line item, never from the client's claim.
        Optional<PlayLineItem> matched = resolveLineItem(google, claimedProductId);
        Instant expiry = matched.map(PlayLineItem::expiryTime).orElse(google.expiryTime());
        boolean entitling = google.state().entitles() && expiry != null && expiry.isAfter(verifiedAt);

        if (!entitling) {
            // 5c first, and always 409. The row is still written when we know which tier it is, so
            // the next wave's RECOVERED / RESTARTED notification has something to update and a
            // re-POST becomes a no-op.
            matched.ifPresent(lineItem -> persist(callerId, token, google, lineItem, expiry, verifiedAt));
            log.info("Purchase {} for user {} is not active: state={} expiry={}",
                    digest(token), callerId, google.rawState(), expiry);
            throw notActive(google.state());
        }

        PlayLineItem lineItem = matched.orElseThrow(() -> new PlayProductMismatchException(
                "None of this purchase's products is offered by this application"));

        // 5b. Google's own link between the purchase and an account, when the client set one.
        // Absent is accepted: a purchase made before the client shipped this, or a promo code
        // redeemed in the Play Store, legitimately carries none, and the token binding in step 3
        // remains the binding protection.
        String claimedAccount = google.obfuscatedExternalAccountId();
        if (claimedAccount != null && !claimedAccount.isBlank()
                && !PlayAccountHash.matches(claimedAccount, callerId)) {
            log.warn("Purchase {} names a different account than caller {} — refusing the claim",
                    digest(token), callerId);
            throw new PlanPurchaseNotOwnedException("This purchase belongs to another account");
        }

        // 6. Write, repairing a lost insert race in a fresh transaction.
        UserSubscription row = persist(callerId, token, google, lineItem, expiry, verifiedAt);

        // 6b. An upgrade: point the replaced row at this one so it stops entitling. Shared with the
        // RTDN handler and the reconciler, so whichever sees the upgrade first stitches it.
        linkResolver.resolve(row, verifiedAt);

        // 7 + 8. Acknowledge outside every transaction, then record it.
        if (!google.acknowledged() && acknowledge(lineItem.productId(), token, callerId)) {
            writer.markAcknowledged(row.getId());
        }

        log.info("Purchase {} for user {} verified: product={} tier={} state={} test={}",
                digest(token), callerId, lineItem.productId(), row.getTier(), google.rawState(),
                google.testPurchase());

        // 9. The full plan status, so the purchase result and the plan screen cannot disagree.
        return planLimits.statusOf(callerId);
    }

    private UserSubscription persist(UUID callerId, String token, PlaySubscription google,
                                     PlayLineItem lineItem, Instant expiry, Instant verifiedAt) {
        Plan tier = catalog.tierOf(lineItem.productId()).orElseThrow(() -> new PlayProductMismatchException(
                "None of this purchase's products is offered by this application"));
        SubscriptionWriter.Snapshot snapshot = new SubscriptionWriter.Snapshot(
                callerId,
                token,
                lineItem.productId(),
                tier,
                google.signupPromotion() ? PurchaseProvenance.PROMO_CODE : PurchaseProvenance.PLAY_PURCHASE,
                google.state(),
                // When Google gave no expiry, store the instant we asked: truthful, already in the
                // past, and therefore already non-entitling. The MATCHED line item's expiry, never
                // the aggregate — with more than one line item the aggregate over-entitles.
                expiry == null ? verifiedAt : expiry,
                google.acknowledged(),
                google.linkedPurchaseToken(),
                google.testPurchase(),
                google.latestOrderId(),
                verifiedAt,
                // The DEFERRED downgrade's future product, from the MATCHED line item. It MUST be
                // on the Snapshot: apply() overwrites every mutable field, and PurchaseSyncer posts
                // the token on every app foreground, so a component left off here would null out
                // the pending product the RTDN handler had just written — the plan screen's "Pro
                // until 14 March, then Standard" would appear and disappear at random.
                lineItem.deferredProductId());
        try {
            return writer.upsert(snapshot);
        } catch (DataIntegrityViolationException race) {
            // A concurrent POST of the same token won the insert. The catch is HERE, outside any
            // transaction, because the writer's transaction is rollback-only by now.
            log.info("Purchase {} lost an insert race; re-reading before continuing", digest(token));
            return writer.refreshOwned(snapshot, callerId);
        }
    }

    /**
     * Google's line item, preferring the one the client claimed and otherwise accepting any line
     * item this application actually offers. Accepting another of our own products is deliberate:
     * a DEFERRED downgrade keeps the OLD product on the token until the term ends, so the client
     * posts what it launched while Google still reports the previous product. Treating that as a
     * mismatch would tell the client to throw away a purchase the user paid for.
     *
     * <p>The fallback picks the HIGHEST tier among our own line items rather than the first one
     * Google happened to list. A multi-line-item purchase would otherwise persist whichever tier
     * came first in a response whose order Google does not promise, so the same purchase could
     * record a different tier on a retry. Highest is also the right direction to err: every
     * candidate here is a product this user genuinely bought, so the worst case is honouring the
     * larger of two things they paid for rather than arbitrarily downgrading them.
     */
    private Optional<PlayLineItem> resolveLineItem(PlaySubscription google, String claimedProductId) {
        List<PlayLineItem> offered = google.lineItems().stream()
                .filter(lineItem -> catalog.tierOf(lineItem.productId()).isPresent())
                .toList();
        return offered.stream()
                .filter(lineItem -> claimedProductId.equals(lineItem.productId()))
                .findFirst()
                .or(() -> offered.stream().max(Comparator.comparing(this::tierOfOffered)));
    }

    /** The tier behind an already-filtered line item; {@code offered} only holds known products. */
    private Plan tierOfOffered(PlayLineItem lineItem) {
        return catalog.tierOf(lineItem.productId()).orElseThrow();
    }

    /**
     * Acknowledgement, with a short bounded retry. A failure is logged at WARN and does NOT fail
     * the request: the entitlement is already committed and correct, a 502 here would make the
     * client re-POST (which cannot help on its own) and would hide a successful purchase behind an
     * error screen. {@code acknowledged = false} is then reported on the plan status, which is what
     * makes the next foreground's re-POST repair it.
     *
     * @return whether Google accepted the acknowledgement
     */
    private boolean acknowledge(String productId, String token, UUID callerId) {
        for (int attempt = 1; attempt <= ACKNOWLEDGE_ATTEMPTS; attempt++) {
            try {
                play.acknowledge(productId, token);
                return true;
            } catch (PlayApiException retryable) {
                if (attempt == ACKNOWLEDGE_ATTEMPTS) {
                    log.warn("Could not acknowledge purchase {} for user {} after {} attempts; "
                                    + "Google will auto-refund it if this is not repaired",
                            digest(token), callerId, ACKNOWLEDGE_ATTEMPTS, retryable);
                    return false;
                }
                sleep(ACKNOWLEDGE_BACKOFF_MILLIS * attempt);
            } catch (PlayPurchaseInvalidException permanent) {
                log.warn("Google refused to acknowledge purchase {} for user {}",
                        digest(token), callerId, permanent);
                return false;
            }
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void requireOwnedBy(UserSubscription row, UUID callerId, String token) {
        if (!row.getUserId().equals(callerId)) {
            log.warn("Purchase {} is already linked to another account; caller {} refused",
                    digest(token), callerId);
            throw new PlanPurchaseNotOwnedException("This purchase belongs to another account");
        }
    }

    private static PlayPurchaseNotActiveException notActive(SubscriptionState state) {
        return new PlayPurchaseNotActiveException("This purchase is not active (" + state + ")");
    }

    /**
     * Twelve hex characters of SHA-256 — enough to correlate a token across log lines, and not the
     * token. Lifted into {@link PurchaseTokens} when the RTDN handler, the reconciler and the
     * cancellation janitor became the other three callers; four private copies of a hash is four
     * chances for one of them to drift and stop correlating with the rest.
     */
    private static String digest(String token) {
        return PurchaseTokens.digest(token);
    }
}
