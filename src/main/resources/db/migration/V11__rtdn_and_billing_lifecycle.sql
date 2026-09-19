-- V11 — what the RTDN handler, the reconciler and account-deletion-with-a-live-subscription need
-- that V10 did not already provide.
--
-- V10 built the ledger and left it unused on purpose ("the handler is OUT OF SCOPE for this wave.
-- The table is in scope because the ordering and retry guarantees have to be designed before
-- anything writes them"). This migration is the other half of that sentence: the handler ships, so
-- the enum pins ship with it.
--
-- NOTHING HERE BACKFILLS OR REWRITES A ROW. user_subscriptions is empty in production and
-- play_notifications has never been written to, so every ADD CONSTRAINT below validates instantly.
--
-- No char(N) anywhere (Hibernate 6.6 validate treats bpchar as a type mismatch and the app refuses
-- to boot), no PostgreSQL native enums, and every enum-ish value is varchar + CHECK matching a Java
-- enum byte for byte with a test that parses this file — PlayNotificationEnumsTest,
-- written the way SubscriptionStateTest is written.

-- ---------------------------------------------------------------------------------------------
-- 1. play_notifications — pin the two enums the handler introduces, and make the ONE row the
--    handler cannot otherwise write writable.
-- ---------------------------------------------------------------------------------------------

-- The sibling field of the RTDN envelope that was populated. These are SIBLINGS, not variants:
-- an envelope carries subscriptionNotification OR voidedPurchaseNotification OR
-- oneTimeProductNotification OR testNotification, and a handler that only looks at the first
-- silently ignores every refund. UNKNOWN is a real stored value — a sibling Google adds after this
-- ships must be RECORDED truthfully and ignored, never dropped, because the payload column is what
-- a corrected handler would later be re-run against. It is ALSO what an undecodable message is
-- recorded under, because at that point nothing is known about which sibling was meant.
ALTER TABLE play_notifications
    ADD CONSTRAINT ck_play_notifications_kind
        CHECK (notification_kind IN ('SUBSCRIPTION', 'VOIDED_PURCHASE', 'ONE_TIME_PRODUCT',
                                     'TEST', 'UNKNOWN'));

-- WHAT THE HANDLER DECIDED. V10 gave the ledger a success flag (processed_at) and a failure string
-- (processing_error) and forbade both at once — which leaves the four *successful* dispositions
-- indistinguishable. "Google sent us an ACTIVE we applied", "Google sent us a stale ACTIVE we
-- discarded", "Google sent us a token we have never seen" and "Google sent us a test ping" all look
-- identical in V10's ledger, and they are the four things somebody debugging a wrong entitlement
-- needs to tell apart at 2am.
--
-- Nullable: a row that has been recorded but not yet handled has no outcome. There is deliberately
-- no DEFAULT — 'PENDING' as an outcome value would be a fifth way to say what processed_at IS NULL
-- already says.
ALTER TABLE play_notifications
    ADD COLUMN outcome varchar(32);

-- The enum pin, and the one constraint PlayNotificationEnumsTest parses.
ALTER TABLE play_notifications
    ADD CONSTRAINT ck_play_notifications_outcome
        CHECK (outcome IS NULL OR outcome IN ('APPLIED', 'DISCARDED_STALE', 'NO_LOCAL_ROW',
                                              'IGNORED', 'MALFORMED', 'FAILED'));

-- The invariant BETWEEN the two columns, which is a different statement from the enum pin and is
-- why they are two constraints rather than one. processed_at keeps V10's meaning exactly —
-- SUCCEEDED — so the four benign outcomes set it and the two terminal-but-unapplied outcomes do
-- not. This is what keeps V10's documented watermark read correct without modification:
--     SELECT max(event_time_millis) FROM play_notifications
--      WHERE purchase_token = :token AND processed_at IS NOT NULL
-- A MALFORMED or FAILED message applied nothing, so it must not advance anybody's watermark.
--
-- It is also why a poison message is NOT marked processed. It keeps processed_at NULL with
-- attempts driven to the ceiling, which drops it out of ix_play_notifications_unprocessed
-- (WHERE processed_at IS NULL AND attempts < 10) exactly as V10 designed, while
-- ck_play_notifications_error_only_while_pending still lets it keep its error string.
ALTER TABLE play_notifications
    ADD CONSTRAINT ck_play_notifications_outcome_matches_processed
        CHECK (
            (processed_at IS NULL
                 AND (outcome IS NULL OR outcome IN ('MALFORMED', 'FAILED')))
            OR
            (processed_at IS NOT NULL
                 AND outcome IN ('APPLIED', 'DISCARDED_STALE', 'NO_LOCAL_ROW', 'IGNORED'))
        );

-- THE MALFORMED ROW MUST BE WRITABLE, AND UNDER V10 IT WAS NOT. This is the correction a review
-- found: the whole point of the MALFORMED outcome is that a message whose `data` is not base64,
-- whose inner notification is not JSON, or whose eventTimeMillis will not parse is recorded once
-- with attempts at the ceiling and never retried again. But those are EXACTLY the three cases in
-- which there is no event_time_millis and no package_name to write, so the insert would have
-- violated V10's NOT NULLs, GlobalExceptionHandler would have answered 409, Pub/Sub would have
-- nacked, and the same garbage would have been redelivered for the full 7-day retention while the
-- ledger recorded nothing at all about the one class of message it exists to preserve.
--
-- payload stays NOT NULL and is defined for this case as THE RAW PUB/SUB ENVELOPE rather than the
-- decoded notification. The envelope always parsed — messageId is read from it, and without a
-- messageId there is no primary key and therefore no row at all — so there is always something
-- truthful to store, and it is the only thing a corrected handler could be re-run against.
ALTER TABLE play_notifications
    ALTER COLUMN event_time_millis DROP NOT NULL;
ALTER TABLE play_notifications
    ALTER COLUMN package_name DROP NOT NULL;

-- ...and ONLY a malformed row may omit them. Everything else still has to say when Google says the
-- event happened and which app it was about.
ALTER TABLE play_notifications
    ADD CONSTRAINT ck_play_notifications_decoded_unless_malformed
        CHECK (outcome = 'MALFORMED'
                   OR (event_time_millis IS NOT NULL AND package_name IS NOT NULL));

COMMENT ON COLUMN play_notifications.payload IS
    'The decoded DeveloperNotification. For outcome = MALFORMED it is the RAW PUB/SUB ENVELOPE '
        'instead, because nothing inside it could be decoded — the envelope is the only truthful '
        'thing there is to keep, and it is what a corrected handler would be re-run against.';

COMMENT ON COLUMN play_notifications.event_time_millis IS
    'Google''s eventTimeMillis, the ordering guard. NULL only for outcome = MALFORMED, where it '
        'could not be parsed (ck_play_notifications_decoded_unless_malformed).';

-- ---------------------------------------------------------------------------------------------
-- 2. user_subscriptions — the deferred change, and the reconciler's sweep index.
-- ---------------------------------------------------------------------------------------------

-- A DEFERRED downgrade keeps the OLD product on the token until the term ends, which is already
-- why PurchaseVerificationService#resolveLineItem accepts any of our own products rather than only
-- the claimed one. What that leaves the user with is a plan screen that cannot say what happens
-- next — and "your plan changes at some point, we won't say to what" is worse than saying nothing.
--
-- purchases.subscriptionsv2.get answers it: lineItem.deferredItemReplacement.productId. Stored as
-- GOOGLE'S PRODUCT ID and not as a tier, deliberately, and the opposite choice from `tier`:
--   * `tier` is frozen at verification time because re-pointing whereis.plans.*.product-id must
--     never silently re-tier a purchase somebody already paid for.
--   * `pending_product_id` is a statement about the FUTURE that has not been paid for yet, it is
--     re-read from Google on every refresh, and the tier behind it is resolved at READ time through
--     PlanCatalog#tierOf. An id this deployment does not configure therefore reports a null
--     pendingTier instead of a wrong one.
-- No CHECK and no FK for the same reason: Google may name a product we have retired.
ALTER TABLE user_subscriptions
    ADD COLUMN pending_product_id varchar(64);

-- The reconciler's ONE hot query: "the live rows Google has not confirmed recently, oldest first,
-- with anything still unacknowledged ahead of them". The leading column is `acknowledged` because
-- that is the LEADING SORT KEY of the query (order by s.acknowledged asc, s.verifiedAt asc) — an
-- index on (verified_at) alone cannot supply that ordering and would leave a sort node behind,
-- which is the mistake a review caught in the first draft of this comment.
--
-- The partial predicate is the same pair of static predicates the entitling index uses, plus the
-- token — an OPERATOR grant has no token and there is nothing at Google to reconcile it against.
--
-- Honest about scale: with a handful of rows the planner will seq-scan this table anyway and the
-- index will look useless. It is here because this is the only query in the system that runs every
-- fifteen minutes forever, and adding an index to a table that has grown is a migration under
-- pressure.
CREATE INDEX ix_user_subscriptions_reconcile
    ON user_subscriptions (acknowledged, verified_at)
    WHERE voided_at IS NULL AND superseded_by IS NULL AND purchase_token IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- 3. play_cancellation_queue — stop charging somebody whose account no longer exists.
-- ---------------------------------------------------------------------------------------------
-- Google Play does NOT cancel a subscription when a user deletes their app account. Before this
-- migration DELETE /users/me removed everything and left the user being billed by Google for a
-- product they can no longer sign in to. That is indefensible, and it is also the exact shape of a
-- problem this codebase has already solved once: a change that must be committed with the database
-- and then carried out against an external system that cannot join the transaction. V6 answered it
-- with storage_deletion_queue + StorageJanitor. This is the same answer, deliberately.
--
-- purchases.subscriptions.cancel(packageName, subscriptionId, token) turns auto-renew OFF and
-- leaves the already-paid term to run out.
--
-- A COMMERCIAL DECISION, NOT A TECHNICAL LIMIT. An earlier draft of this comment claimed the
-- pinned androidpublisher revision exposes no revoke method and that an automatic refund was
-- therefore impossible. That is FALSE: v3-rev20260909-2.0.0 contains
-- Purchases$Subscriptionsv2$Revoke and Purchases$Subscriptionsv2$Cancel as well as the v1
-- purchases.subscriptions.cancel used here (only `acknowledge` is genuinely v1-only). Cancelling
-- rather than revoking is a CHOICE: revoke refunds the user and ends access immediately, which is
-- not a decision this application makes on the user's behalf without being asked. Whoever revisits
-- it should know the alternative is one method call away, not unavailable.
--
-- NO user_id AND NO FOREIGN KEY, and that is the point: the user row is gone by the time anything
-- reads this table. A FK would make the queue undrainable in exactly the case it exists for.
CREATE TABLE play_cancellation_queue (
    id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- A bearer credential (§6: never logged above DEBUG — log PurchaseTokens#digest instead).
    -- `text` for the same reason user_subscriptions.purchase_token is text: Google documents no
    -- maximum length and no stable format.
    purchase_token  text         NOT NULL,

    -- purchases.subscriptions.cancel is the v1 endpoint and needs the product id.
    product_id      varchar(64)  NOT NULL,

    -- Why this row exists. One value today; the column exists because the second reason (an
    -- operator closing an abusive account) is foreseeable and a boolean would not survive it.
    reason          varchar(32)  NOT NULL,

    attempts        int          NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL DEFAULT now(),
    last_error      text,
    created_at      timestamptz  NOT NULL DEFAULT now(),

    -- One pending cancellation per token.
    --
    -- THE JUSTIFICATION AN EARLIER DRAFT GAVE FOR THIS CONSTRAINT WAS WRONG, and the correction
    -- matters because it changes how the row must be WRITTEN. It claimed "the same Play account can
    -- be signed into two whereis accounts, and both rows would name the same token" — but V10's
    -- ux_user_subscriptions_purchase_token is GLOBAL, so two live user_subscriptions rows can never
    -- carry the same token and that collision is unrepresentable.
    --
    -- The reachable collision is SEQUENTIAL: account A enqueues token T and is deleted (its
    -- user_subscriptions row cascades away, freeing T); the janitor stalls on Play errors or has
    -- simply not run yet; the same person re-registers as B and the client's queryPurchasesAsync
    -- re-posts T on the very first foreground (cancel only turns auto-renew off, so Play keeps
    -- reporting the purchase for the rest of the paid term); B deletes their account; the second
    -- enqueue hits this constraint.
    --
    -- That insert happens inside AccountDeletionService's single @Transactional method, so a
    -- violation would roll the WHOLE deletion back and answer 409 on the Play-mandated deletion
    -- endpoint — the identical failure class V10's composite self-FK exists to prevent. The
    -- constraint is therefore kept but written through
    --     INSERT ... SELECT ... ON CONFLICT (purchase_token) DO NOTHING
    -- (SubscriptionCancellationService#enqueueFor), so a second enqueue is a silent no-op.
    -- Cancelling once is sufficient: it is the same subscription.
    CONSTRAINT ux_play_cancellation_queue_token UNIQUE (purchase_token),
    CONSTRAINT ck_play_cancellation_queue_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_play_cancellation_queue_reason CHECK (reason IN ('ACCOUNT_DELETED'))
);

-- Exactly ix_sdq_next_attempt's job, for exactly the same janitor shape.
CREATE INDEX ix_play_cancellation_queue_due
    ON play_cancellation_queue (next_attempt_at);
