package az.technest.whereis.plan.rtdn;

import az.technest.whereis.plan.PlanPurchaseNotOwnedException;
import az.technest.whereis.plan.PurchaseTokens;
import az.technest.whereis.plan.SubscriptionLinkResolver;
import az.technest.whereis.plan.SubscriptionSnapshots;
import az.technest.whereis.plan.SubscriptionWriter;
import az.technest.whereis.plan.UserSubscription;
import az.technest.whereis.plan.UserSubscriptionRepository;
import az.technest.whereis.plan.play.PlayAccountHash;
import az.technest.whereis.plan.play.PlayProperties;
import az.technest.whereis.plan.play.PlayPurchaseUnknownException;
import az.technest.whereis.plan.play.PlaySubscription;
import az.technest.whereis.plan.play.PlaySubscriptionsApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The Google Play real-time developer notification handler: everything that happens after
 * {@link RtdnController} has authenticated the request.
 *
 * <p><strong>NO {@code @Transactional} ANYWHERE ON THIS CLASS</strong>, the same shape as
 * {@code AssistantService} and {@code PurchaseVerificationService}. Every database step is a method
 * on {@link PlayNotificationLedger} or {@code SubscriptionWriter} reached through the Spring proxy,
 * so the one Google call — {@code subscriptionsv2.get} on the REFRESH path — sits strictly between
 * transactions. {@code OwnershipScopingArchTest} enforces it; {@code NoTransactionAroundThePlayPortTest}
 * additionally asserts this class carries no class-level {@code @Transactional}, because the
 * ArchUnit rule inspects each method's OWN annotations and a class-level one would slip past it.
 *
 * <p><strong>NOTHING ESCAPES.</strong> {@link #handle} catches every exception class this flow can
 * produce and turns it into a {@link Verdict} with an explicit status. That is not defensive
 * habit: {@code GlobalExceptionHandler} is an unrestricted {@code @RestControllerAdvice}, so a
 * {@code PlayApiException} reaching it becomes 502, a {@code PlayPurchaseInvalidException} 400 and a
 * {@code PlanPurchaseNotOwnedException} 409 — none of which appear in the ack table, all of which
 * Pub/Sub nacks, and every one of which bypasses {@code ledger.finish} so the ledger records
 * nothing for precisely the cases somebody is debugging.
 *
 * <p>THE STATUS CODE IS THE ACK DECISION. Pub/Sub retries anything that is not 2xx, so:
 * <table>
 *   <caption>The ack decision table</caption>
 *   <tr><th>Situation</th><th>HTTP</th><th>Ledger</th></tr>
 *   <tr><td>body unreadable, or {@code messageId} missing</td><td>200</td>
 *       <td>nothing written — there is no key to write under. WARN</td></tr>
 *   <tr><td>{@code data} not base64, notification not JSON, {@code eventTimeMillis} unparseable</td>
 *       <td>200</td><td>MALFORMED, attempts at the ceiling, {@code processed_at} NULL</td></tr>
 *   <tr><td>another app's {@code packageName}</td><td>200</td><td>IGNORED, processed. WARN</td></tr>
 *   <tr><td>{@code testNotification}</td><td>200</td><td>IGNORED, processed. INFO</td></tr>
 *   <tr><td>{@code oneTimeProductNotification} or an unknown sibling</td><td>200</td><td>IGNORED</td></tr>
 *   <tr><td>duplicate {@code messageId} (processed, exhausted, or in flight elsewhere)</td>
 *       <td>200</td><td>untouched</td></tr>
 *   <tr><td>applied to a row</td><td>200</td><td>APPLIED</td></tr>
 *   <tr><td>older than the watermark, or another writer's answer is newer</td><td>200</td>
 *       <td>DISCARDED_STALE</td></tr>
 *   <tr><td>token unknown and unattributable</td><td>200</td><td>NO_LOCAL_ROW</td></tr>
 *   <tr><td>{@code PlayPurchaseUnknownException} (Google answered 404)</td><td>200</td>
 *       <td>IGNORED. WARN — definitive, retrying cannot help, and NOTHING is revoked on the
 *       strength of a Google error</td></tr>
 *   <tr><td>{@code PlanPurchaseNotOwnedException} (the token raced another account)</td><td>200</td>
 *       <td>IGNORED. WARN — retrying cannot help either</td></tr>
 *   <tr><td>{@code PlayApiException}, a 400 from Google, or any other failure, under the ceiling</td>
 *       <td>500</td><td>{@code processing_error} set, {@code processed_at} NULL, still queued</td></tr>
 *   <tr><td>the same, at the ceiling</td><td>200</td><td>FAILED, {@code processed_at} NULL</td></tr>
 * </table>
 *
 * <p>A 400 from Google is retried rather than ignored on purpose: {@code translate} maps 400 to
 * {@code PlayPurchaseInvalidException} and a 400 can be OUR bug, while marking the message
 * processed would advance the token's watermark irreversibly and a redelivery would then be
 * discarded. A 404 is different — that is Google answering definitively — and is the one Google
 * error that ends the message.
 *
 * <p><strong>A poison message cannot retry forever</strong>, for two independent reasons. Ours:
 * {@code attempts} is capped at {@code whereis.play.rtdn.max-attempts} (10, which must equal the
 * literal in {@code ix_play_notifications_unprocessed}), and at the ceiling this answers 200
 * regardless of the failure, dropping the row out of the work-queue index exactly as V10 designed.
 * Google's: the subscription is configured with exponential backoff and a 7-day retention, which is
 * a Console setting and a runbook line. The repair for a message we gave up on is the reconciler.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RtdnService {

    /** What the controller answers, and what the ledger recorded. */
    public record Verdict(int status, PlayNotificationOutcome outcome) {

        static Verdict ok(PlayNotificationOutcome outcome) {
            return new Verdict(200, outcome);
        }

        static final Verdict ACK_WITHOUT_LEDGER = new Verdict(200, null);
        static final Verdict RETRY = new Verdict(500, null);
    }

    private final ObjectMapper mapper;
    private final PlayNotificationLedger ledger;
    private final UserSubscriptionRepository subscriptions;
    private final SubscriptionWriter writer;
    private final SubscriptionSnapshots snapshots;
    private final SubscriptionLinkResolver linkResolver;
    private final PlaySubscriptionsApi play;
    private final PlayProperties playProperties;
    private final RtdnProperties properties;

    /**
     * The order of operations, with the transaction boundaries:
     * <pre>
     * [no tx] parse envelope + decode + parse notification
     * [tx]    ledger.insertNew  |  ledger.claim  (the lease)
     * [no tx] classify: kind + type
     * [NO TX — EXTERNAL] play.get(token)        ← only on the REFRESH path
     * [tx]    writer.applyNotification / markVoided / upsert
     * [tx]    writer.markSuperseded             ← only when a link resolves
     * [tx]    ledger.finish
     * </pre>
     * The REVOKE path makes no Google call at all: a refund must not be blocked by a Play outage.
     */
    public Verdict handle(byte[] body) {
        Map<String, Object> rawEnvelope;
        RtdnEnvelope envelope;
        try {
            rawEnvelope = readMap(body);
            envelope = mapper.convertValue(rawEnvelope, RtdnEnvelope.class);
        } catch (RuntimeException unreadable) {
            log.warn("Discarding an unreadable RTDN envelope: {}", unreadable.getMessage());
            return Verdict.ACK_WITHOUT_LEDGER;
        }
        RtdnEnvelope.Message message = envelope.message();
        if (message == null || message.messageId() == null || message.messageId().isBlank()) {
            // No key to write a ledger row under, so there is nothing to record and nothing a retry
            // could do differently.
            log.warn("Discarding an RTDN push with no messageId");
            return Verdict.ACK_WITHOUT_LEDGER;
        }
        String messageId = message.messageId();
        Instant publishTime = message.publishTime() == null ? Instant.now() : message.publishTime();

        DeveloperNotification notification;
        Map<String, Object> decoded;
        long eventTimeMillis;
        try {
            byte[] raw = Base64.getDecoder().decode(message.data() == null ? "" : message.data());
            decoded = readMap(raw);
            notification = mapper.convertValue(decoded, DeveloperNotification.class);
            eventTimeMillis = Long.parseLong(notification.eventTimeMillis().trim());
        } catch (RuntimeException malformed) {
            return recordMalformed(messageId, publishTime, rawEnvelope, malformed);
        }

        PlayNotificationKind kind = notification.kind();
        PlayNotification row = PlayNotification.builder()
                .messageId(messageId)
                .publishTime(publishTime)
                .eventTimeMillis(eventTimeMillis)
                .packageName(notification.packageName())
                .notificationKind(kind)
                .notificationType(typeOf(notification))
                .purchaseToken(tokenOf(notification))
                .productId(productOf(notification))
                .orderId(orderOf(notification))
                .payload(decoded)
                .receivedAt(Instant.now())
                // Bumped BEFORE the work, so a crash still counts and a poison message still
                // converges on its ceiling.
                .attempts(1)
                .lastAttemptAt(Instant.now())
                .build();
        try {
            ledger.insertNew(row);
        } catch (RuntimeException duplicate) {
            // A redelivery — or a concurrent second delivery of the same message. The LEASE settles
            // which: a conditional UPDATE that claims a pending, under-ceiling row. Zero rows means
            // somebody else owns it, it is already finished, or it is already exhausted; all three
            // answer 200 with nothing touched. A primary-key collision on its own could not tell a
            // crashed earlier delivery from a live one.
            if (ledger.claim(messageId, properties.maxAttempts()) == 0) {
                log.debug("RTDN {} is a duplicate that is finished, exhausted, or in flight elsewhere",
                        messageId);
                return Verdict.ACK_WITHOUT_LEDGER;
            }
        }

        try {
            PlayNotificationOutcome outcome = process(notification, kind, eventTimeMillis);
            ledger.finish(messageId, outcome, null);
            return Verdict.ok(outcome);
        } catch (PlayPurchaseUnknownException definitive) {
            // Google answered 404. Retrying cannot help, and NOTHING is revoked on the strength of
            // a Google error.
            log.warn("RTDN {} names a purchase Google does not know; ignoring it", messageId);
            ledger.finish(messageId, PlayNotificationOutcome.IGNORED, null);
            return Verdict.ok(PlayNotificationOutcome.IGNORED);
        } catch (PlanPurchaseNotOwnedException raced) {
            // An attributed token that another account created in the meantime. user_id is
            // updatable = false, so there is nothing to repair and nothing a retry could change.
            log.warn("RTDN {} tried to attribute a token that belongs to another account", messageId);
            ledger.finish(messageId, PlayNotificationOutcome.IGNORED, null);
            return Verdict.ok(PlayNotificationOutcome.IGNORED);
        } catch (RuntimeException failure) {
            return recordFailure(messageId, failure);
        }
    }

    /**
     * The three undecodable shapes, recorded once and never retried.
     *
     * <p>{@code payload} is THE RAW PUB/SUB ENVELOPE here, not the decoded notification — nothing
     * decoded, and the envelope is the only truthful thing there is to keep (it parsed, or there
     * would be no {@code messageId} and hence no row at all). {@code event_time_millis} and
     * {@code package_name} are NULL, which V11 permits for exactly this outcome and nothing else.
     *
     * <p>{@code attempts} goes straight to the ceiling, which drops the row out of
     * {@code ix_play_notifications_unprocessed} and is what makes "a poison message cannot retry
     * forever" true rather than aspirational.
     */
    private Verdict recordMalformed(String messageId, Instant publishTime,
                                    Map<String, Object> rawEnvelope, Throwable cause) {
        log.warn("RTDN {} could not be decoded ({}); recording it as MALFORMED and acking",
                messageId, cause.getClass().getSimpleName());
        PlayNotification row = PlayNotification.builder()
                .messageId(messageId)
                .publishTime(publishTime)
                .eventTimeMillis(null)
                .packageName(null)
                .notificationKind(PlayNotificationKind.UNKNOWN)
                .payload(rawEnvelope)
                .receivedAt(Instant.now())
                .attempts(properties.maxAttempts())
                .lastAttemptAt(Instant.now())
                .outcome(PlayNotificationOutcome.MALFORMED)
                .processingError(describe(cause))
                .build();
        try {
            ledger.insertNew(row);
        } catch (RuntimeException duplicate) {
            log.debug("RTDN {} was already recorded as malformed", messageId);
        }
        return Verdict.ok(PlayNotificationOutcome.MALFORMED);
    }

    /** Retryable under the ceiling, terminal at it. Never a 502, never a 400, never an ApiError body. */
    private Verdict recordFailure(String messageId, RuntimeException failure) {
        int attempts = ledger.load(messageId).map(PlayNotification::getAttempts).orElse(properties.maxAttempts());
        if (attempts >= properties.maxAttempts()) {
            log.error("RTDN {} failed {} times; giving up. The reconciler is the repair.",
                    messageId, attempts, failure);
            ledger.finish(messageId, PlayNotificationOutcome.FAILED, describe(failure));
            return Verdict.ok(PlayNotificationOutcome.FAILED);
        }
        log.warn("RTDN {} failed on attempt {}: {}", messageId, attempts, describe(failure));
        ledger.recordFailure(messageId, describe(failure));
        return Verdict.RETRY;
    }

    private PlayNotificationOutcome process(DeveloperNotification notification, PlayNotificationKind kind,
                                            long eventTimeMillis) {
        if (!playProperties.packageName().equals(notification.packageName())) {
            // A valid Google token plus another app's package means the Pub/Sub topic is shared.
            // That is a Console problem, not a code problem, and acting on it would be acting on a
            // purchase that is not ours.
            log.warn("RTDN names package '{}', not ours; ignoring it", notification.packageName());
            return PlayNotificationOutcome.IGNORED;
        }
        return switch (kind) {
            case VOIDED_PURCHASE -> revokeFromVoid(notification.voidedPurchaseNotification(), eventTimeMillis);
            case SUBSCRIPTION -> handleSubscription(notification.subscriptionNotification(), eventTimeMillis);
            case TEST -> {
                // The Console's "Send test notification" button. It must produce a visible,
                // greppable line, because this is how the whole pipeline is confirmed wired.
                log.info("RTDN test notification received — the Pub/Sub push path is working");
                yield PlayNotificationOutcome.IGNORED;
            }
            case ONE_TIME_PRODUCT, UNKNOWN -> PlayNotificationOutcome.IGNORED;
        };
    }

    /**
     * A refund or a chargeback, applied from the notification alone with NO Google call: a refund
     * must not be blocked by a Play API outage, and {@code subscriptionsv2.get} would add nothing
     * the notification does not already state.
     *
     * <p><strong>The decision is made by the TOKEN, not by {@code productType}.</strong> Testing
     * {@code productType != 1} fails OPEN — an absent or null field makes it true and every refund
     * is silently ignored, which is the exact fail-open shape this whole path exists to prevent, one
     * level down. A hit in {@code user_subscriptions} IS a subscription void by construction, so
     * {@code productType} is only consulted to recognise an explicit {@code 2} (a one-time product,
     * which we do not sell) when there is no local row anyway.
     */
    private PlayNotificationOutcome revokeFromVoid(DeveloperNotification.VoidedPurchaseNotification voided,
                                                  long eventTimeMillis) {
        if (voided == null || voided.purchaseToken() == null || voided.purchaseToken().isBlank()) {
            return PlayNotificationOutcome.IGNORED;
        }
        // The deliberately UNSCOPED finder, and this is a legitimate use of it: the token is a
        // global key issued by Google and the notification carries no account. There is no userId to
        // scope by and inventing one is the failure this whole design avoids.
        Optional<UserSubscription> row = subscriptions.findByPurchaseToken(voided.purchaseToken());
        if (row.isEmpty()) {
            if (Integer.valueOf(2).equals(voided.productType())) {
                return PlayNotificationOutcome.IGNORED;
            }
            // A void with no row to void is not a reason to fabricate an entitlement record. The
            // 7-day look-back sweep re-applies it once the client's foreground sync creates the row.
            return PlayNotificationOutcome.NO_LOCAL_ROW;
        }
        return applyVoid(row.get(), Instant.now(), voided.orderId(), eventTimeMillis, "RTDN void");
    }

    /**
     * {@code SUBSCRIPTION_REVOKED} — the one subscription type applied from the notification alone.
     * Same write as the void sibling, same write-once rule, so the two entry points cannot disagree.
     */
    private PlayNotificationOutcome handleSubscription(DeveloperNotification.SubscriptionNotification notification,
                                                       long eventTimeMillis) {
        if (notification == null || notification.purchaseToken() == null
                || notification.purchaseToken().isBlank()) {
            return PlayNotificationOutcome.IGNORED;
        }
        String token = notification.purchaseToken();
        if (SubscriptionNotificationType.actionOf(notification.notificationType())
                == SubscriptionNotificationType.RtdnAction.REVOKE) {
            Optional<UserSubscription> row = subscriptions.findByPurchaseToken(token);
            if (row.isEmpty()) {
                return PlayNotificationOutcome.NO_LOCAL_ROW;
            }
            return applyVoid(row.get(), Instant.now(), null, eventTimeMillis, "RTDN revoke");
        }
        return refresh(token, eventTimeMillis);
    }

    /**
     * Every applied revoke is logged at WARN with the digest, the userId and the source.
     *
     * <p>That is not noise. A revocation is the only thing this system applies from a notification
     * with no Google corroboration, {@code voided_at} is write-once, a REFRESH may never clear it,
     * and the reconciler refuses to run against a voided row — so an erroneous revoke is permanent
     * unless somebody can FIND it. The sanctioned repair is in {@code deploy/README.md}, and it
     * needs this line to know which token to repair.
     */
    private PlayNotificationOutcome applyVoid(UserSubscription row, Instant voidedAt, String orderId,
                                              long eventTimeMillis, String source) {
        boolean firstVoid = writer.markVoided(row.getId(), voidedAt, orderId, eventTimeMillis);
        if (firstVoid) {
            log.warn("REVOKED subscription {} of user {} ({}): entitlement ends immediately",
                    PurchaseTokens.digest(row.getPurchaseToken()), row.getUserId(), source);
            return PlayNotificationOutcome.APPLIED;
        }
        // Already voided. Write-once, so nothing moved — and saying APPLIED would claim otherwise.
        return PlayNotificationOutcome.DISCARDED_STALE;
    }

    /**
     * REFRESH: ask Google, write what it says. The default for every type including ones added after
     * this ships, which is why "a missing type is a silent bug" does not apply here.
     */
    private PlayNotificationOutcome refresh(String token, long eventTimeMillis) {
        Optional<UserSubscription> known = subscriptions.findByPurchaseToken(token);
        if (known.isEmpty()) {
            return attribute(token, eventTimeMillis);
        }
        UserSubscription row = known.get();
        Long watermark = row.getLastEventTime();
        if (watermark != null && eventTimeMillis <= watermark) {
            // A cheap pre-check that saves a Google call. The REAL guard is re-evaluated inside the
            // write transaction after SELECT … FOR UPDATE; this one only avoids the round trip.
            return PlayNotificationOutcome.DISCARDED_STALE;
        }
        Instant expectedVerifiedAt = row.getVerifiedAt();
        PlaySubscription google = play.get(token);
        Instant verifiedAt = Instant.now();
        SubscriptionWriter.Snapshot snapshot = snapshots.refreshOf(row, google, verifiedAt);
        if (!writer.applyNotification(row.getId(), snapshot, eventTimeMillis, expectedVerifiedAt)) {
            return PlayNotificationOutcome.DISCARDED_STALE;
        }
        log.info("RTDN refreshed subscription {} of user {}: state={} tier={}",
                PurchaseTokens.digest(token), row.getUserId(), google.rawState(), snapshot.tier());
        writer.find(row.getId()).ifPresent(refreshed -> linkResolver.resolve(refreshed, verifiedAt));
        return PlayNotificationOutcome.APPLIED;
    }

    /**
     * A notification for a token this server has never seen — legitimate and expected: a purchase
     * made with the app closed, a promo code redeemed in the Play Store, or an upgrade whose RTDN
     * beats the client's foreground sync.
     *
     * <p>{@code user_subscriptions.user_id} is NOT NULL and {@code obfuscatedExternalAccountId} is a
     * one-way SHA-256 with no reverse lookup, so <strong>the handler does not invent an owner.</strong>
     * Exactly one attribution is allowed, and only for an upgrade: Google reports a
     * {@code linkedPurchaseToken} that resolves to a local row (global finder, READ-ONLY, candidate
     * userId only), AND the account hash is present, AND it matches that candidate.
     *
     * <p><strong>An ABSENT hash fails closed here</strong> — the opposite of the verify endpoint's
     * rule, deliberately. There the caller's JWT proves the claim; here there is no caller, and our
     * own client makes {@code setObfuscatedAccountId} mandatory on every launch, so a missing hash
     * means the purchase did not come from us. Binding wrongly is permanent ({@code user_id} is
     * {@code updatable = false}) and would give the real owner 409 PLAN_PURCHASE_NOT_OWNED forever.
     */
    private PlayNotificationOutcome attribute(String token, long eventTimeMillis) {
        Long watermark = ledger.watermarkOf(token);
        if (watermark != null && eventTimeMillis <= watermark) {
            return PlayNotificationOutcome.DISCARDED_STALE;
        }
        PlaySubscription google = play.get(token);
        String linkedToken = google.linkedPurchaseToken();
        if (linkedToken == null || linkedToken.isBlank()) {
            return PlayNotificationOutcome.NO_LOCAL_ROW;
        }
        Optional<UserSubscription> linked = subscriptions.findByPurchaseToken(linkedToken);
        if (linked.isEmpty()) {
            return PlayNotificationOutcome.NO_LOCAL_ROW;
        }
        UUID candidate = linked.get().getUserId();
        if (!PlayAccountHash.matches(google.obfuscatedExternalAccountId(), candidate)) {
            return PlayNotificationOutcome.NO_LOCAL_ROW;
        }
        Instant verifiedAt = Instant.now();
        Optional<SubscriptionWriter.Snapshot> snapshot = snapshots.creationOf(candidate, token, google, verifiedAt);
        if (snapshot.isEmpty()) {
            log.warn("RTDN attributed purchase {} offers none of this deployment's products; ignoring it",
                    PurchaseTokens.digest(token));
            return PlayNotificationOutcome.IGNORED;
        }
        UserSubscription created = upsertRepairingRace(snapshot.get(), candidate);
        writer.advanceWatermark(created.getId(), eventTimeMillis);
        log.info("RTDN created subscription {} for user {} from a linked upgrade",
                PurchaseTokens.digest(token), candidate);
        writer.find(created.getId()).ifPresent(row -> linkResolver.resolve(row, verifiedAt));
        return PlayNotificationOutcome.APPLIED;
    }

    /**
     * The same catch-outside-the-transaction repair {@code PurchaseVerificationService#persist}
     * uses: a concurrent POST of the same token can win the insert, and a constraint violation
     * leaves the writer's persistence context unusable, so the re-read must happen in a fresh one.
     */
    private UserSubscription upsertRepairingRace(SubscriptionWriter.Snapshot snapshot, UUID candidate) {
        try {
            return writer.upsert(snapshot);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            return writer.refreshOwned(snapshot, candidate);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(byte[] json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("not JSON", e);
        }
    }

    private static Integer typeOf(DeveloperNotification notification) {
        if (notification.subscriptionNotification() != null) {
            return notification.subscriptionNotification().notificationType();
        }
        if (notification.voidedPurchaseNotification() != null) {
            return notification.voidedPurchaseNotification().productType();
        }
        if (notification.oneTimeProductNotification() != null) {
            return notification.oneTimeProductNotification().notificationType();
        }
        return null;
    }

    private static String tokenOf(DeveloperNotification notification) {
        if (notification.subscriptionNotification() != null) {
            return notification.subscriptionNotification().purchaseToken();
        }
        if (notification.voidedPurchaseNotification() != null) {
            return notification.voidedPurchaseNotification().purchaseToken();
        }
        if (notification.oneTimeProductNotification() != null) {
            return notification.oneTimeProductNotification().purchaseToken();
        }
        return null;
    }

    private static String productOf(DeveloperNotification notification) {
        if (notification.subscriptionNotification() != null) {
            return notification.subscriptionNotification().subscriptionId();
        }
        if (notification.oneTimeProductNotification() != null) {
            return notification.oneTimeProductNotification().sku();
        }
        return null;
    }

    private static String orderOf(DeveloperNotification notification) {
        return notification.voidedPurchaseNotification() == null
                ? null : notification.voidedPurchaseNotification().orderId();
    }

    /** An error STRING, never a stack trace, never a token (§6). */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
