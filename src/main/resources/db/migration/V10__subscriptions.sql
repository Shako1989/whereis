-- The four-tier ladder, the Play subscription record, and the RTDN ledger.
--
-- Three independent things, one migration, because they are one product decision: the tier ladder
-- is meaningless without somewhere to record what was bought, and the record is unsafe without the
-- ledger that will later replay Google's notifications in order.
--
-- What this migration does NOT do, deliberately:
--   * no UPDATE of users.plan. Same reason as V9: nothing is grandfathered, and a stamped row
--     could never express "these four testers unlimited, those eight limited". PlanTest asserts
--     the absence across EVERY migration that touches users.plan, not just V9.
--   * no backfill of user_subscriptions. Nobody has ever bought anything; the table starts empty.
--   * no char(N) anywhere. Hibernate 6.6 validate treats bpchar as a type mismatch and the app
--     refuses to boot (see §4 of the project spec).
--   * no PostgreSQL native enums. Every enum-ish value is varchar + CHECK matching a Java enum
--     byte for byte, and a test parses this file for each one that HAS a Java enum today:
--     PlanTest (users.plan), SubscriptionTierTest (tier), SubscriptionStateTest (state),
--     PurchaseProvenanceTest (provenance). play_notifications.notification_kind deliberately has
--     NO CHECK: nothing writes that table this wave, there is no Java enum to pin it against, and
--     an unguarded CHECK derived from nothing can only bite the handler later as a dropped
--     notification. It ships with the handler, in the same migration as the enum that pins it.
--
-- WHAT `ddl-auto: validate` DOES AND DOES NOT GUARANTEE (the comment this replaces was wrong):
-- Hibernate's schema validator checks that the columns of MAPPED entities exist with a compatible
-- JDBC type; it does NOT report extra columns, does NOT compare varchar lengths, and says nothing
-- at all about a table with no entity. So `user_subscriptions` is only as validated as
-- UserSubscription maps, and `play_notifications` — deliberately unmapped this wave — is not
-- validated by Hibernate in any way. The real validate-killer in this schema is bpchar, which this
-- file avoids everywhere. UserSubscriptionMappingIT inserts and reads back a FULL row through the
-- entity so that every NOT NULL column here is proven mapped and writable.

-- ---------------------------------------------------------------------------------------------
-- 1. Widen users.plan for the new ladder.
-- ---------------------------------------------------------------------------------------------
-- FREE < STANDARD < PRO < MAX < UNLIMITED. The first four are what a paid ladder offers; the
-- fifth is the OPERATOR-ONLY grant that survives from V9 and is NEVER purchasable — it is the
-- only value that cannot appear in user_subscriptions.tier, which is what keeps "granted" and
-- "paid" distinguishable no matter what billing later writes.
--
-- The column stays varchar(20) ('STANDARD' is 8 characters) and stays written by V9's DEFAULT or
-- by an operator's UPDATE, and by NOTHING else. An RTDN expiry writing FREE back here would erase
-- a hand-made grant; see PlanLimitEnforcer#effectiveTierOf, which combines this column with the
-- subscription table instead of letting either overwrite the other.
--
-- ONE-WAY DOOR. users.plan is @Enumerated(EnumType.STRING). The moment an operator runs
-- `UPDATE users SET plan = 'PRO'`, rolling the container back to a pre-V10 image — whose Plan enum
-- is {FREE, UNLIMITED} — makes every read of that row throw, i.e. a 500 on GET /users/me/plan and
-- on every creation. Flyway does not undo this migration. deploy/README.md Step 10 carries the
-- paste-ready repair statement; do not grant STANDARD/PRO/MAX until the release has settled.
ALTER TABLE users
    DROP CONSTRAINT ck_users_plan;

ALTER TABLE users
    ADD CONSTRAINT ck_users_plan
        CHECK (plan IN ('FREE', 'STANDARD', 'PRO', 'MAX', 'UNLIMITED'));

-- Granting a tier by hand (deploy/README.md Step 10) is still one statement:
--     UPDATE users SET plan = 'PRO' WHERE lower(email) = lower('someone@example.com');
-- and revoking it is the same statement with 'FREE'. Revoking never touches a paid subscription,
-- because the entitlement is the HIGHER of the two.

-- ---------------------------------------------------------------------------------------------
-- 2. user_subscriptions — one row per Google purchase token.
-- ---------------------------------------------------------------------------------------------
-- KEYED ON THE PURCHASE TOKEN, BUT NOT BY IT. The token is the natural key and is UNIQUE here;
-- the PRIMARY KEY is a uuid, for three reasons:
--   (a) superseded_by is a self-reference. Pointing it at a uuid keeps the referencing column 16
--       bytes instead of duplicating an opaque token of unbounded documented length.
--   (b) Google documents no maximum token length and no stable format. A primary key whose width
--       is a vendor's undocumented choice is a schema that can be broken from outside.
--   (c) Every other table here has a uuid PK generated by Hibernate @UuidGenerator. One natural-key
--       exception would be the only table an operator, a mapper and a future FK all treat
--       differently.
-- Idempotency is unaffected: the UNIQUE constraint on purchase_token is what makes a replayed
-- POST a no-op, and a constraint enforces that just as hard as a key would.
CREATE TABLE user_subscriptions (
    -- DEFAULT so an operator can hand-write a grant row; Hibernate always supplies its own.
    id                    uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- Google's purchase token. `text`, not varchar(N): Google documents no maximum length and no
    -- stable format, and a vendor-chosen width that can reject a purchase the user paid for is a
    -- schema that can be broken from outside. text and varchar are the same storage in PostgreSQL;
    -- the real ceiling is the UNIQUE btree's ~2704-byte entry limit, which is far beyond the ~250
    -- characters observed. NULL only for provenance = 'OPERATOR' (see ck_..._token_required):
    -- UNIQUE tolerates multiple NULLs, so hand-written grants do not collide with each other.
    -- This value is a bearer credential: never logged above DEBUG (§6).
    purchase_token        text,

    -- The Play product as Google reported it, and the tier it mapped to AT VERIFICATION TIME.
    -- tier is denormalized on purpose: whereis.plans.* can be re-pointed later, and a purchase
    -- must never be silently re-tiered by an edit to configuration.
    product_id            varchar(64),
    tier                  varchar(20)   NOT NULL,

    -- How this row came to exist. OPERATOR is a hand-inserted, TIME-BOXED grant, distinct from the
    -- permanent users.plan grant: it is the only way to give a tester paid-tier access with an end
    -- date. Because ck_user_subscriptions_tier forbids UNLIMITED here, an OPERATOR row can grant
    -- STANDARD, PRO or MAX only — an unlimited grant is permanent and lives on users.plan.
    -- PROMO_CODE is a Play promotional code redemption, which arrives as an ordinary purchase and
    -- is told apart only by the line item's signupPromotion — never by price, which reports the
    -- FULL amount during a promo trial.
    provenance            varchar(20)   NOT NULL,

    -- Google's subscription state, mapped through our own enum. UNKNOWN is a real stored value:
    -- an unmapped state must be recorded truthfully and must NOT entitle.
    state                 varchar(32)   NOT NULL,

    -- The fail-closed guard. NOT NULL on purpose: a NULL would have to mean either "never expires"
    -- or "not entitling", and every reader would have to pick. When Google reports no expiry the
    -- writer stores the instant it asked, which is truthful and already in the past.
    entitled_until        timestamptz   NOT NULL,

    -- Acknowledged to Google within 3 days (5 MINUTES for a test purchase, which is every purchase
    -- on the closed track) or Google auto-refunds and revokes. false means the retry is still owed:
    -- the verify endpoint re-verifies and re-acknowledges any entitling row it finds with
    -- acknowledged = false, and reports the flag on GET /users/me/plan so the client can re-post.
    acknowledged          boolean       NOT NULL DEFAULT false,

    -- Google's linkedPurchaseToken: the token this purchase REPLACES on an upgrade/downgrade. Kept
    -- raw because the row it names may not exist locally (a purchase made before this feature
    -- shipped, or one that never reached the verify endpoint).
    linked_purchase_token text,

    -- Our own resolution of that link, set only when the linked token is found locally AND belongs
    -- to the same user. That last part is a DB guarantee, not a convention: the FK below is
    -- COMPOSITE, (superseded_by, user_id) -> (id, user_id), the same pattern V4 uses for
    -- fk_locations_parent_same_space. Without it a chain could cross users — linkedPurchaseToken is
    -- scoped to a PLAY account, not a whereis account, and one Play account signed into two whereis
    -- accounts is the documented abuse case — and then deleting one user would cascade a row the
    -- other still references, aborting the whole single-transaction AccountDeletionService and
    -- breaking the Play-mandated DELETE /users/me. MATCH SIMPLE means a NULL superseded_by skips
    -- the check entirely. NO ACTION (checked at end of statement) so the users cascade can still
    -- delete a whole same-user chain in one statement.
    -- NEXT WAVE'S CONTRACT: resolve linkedPurchaseToken with a userId-SCOPED finder, never the
    -- global findByPurchaseToken, or this FK turns a cross-account link into a hard error at the
    -- worst possible moment. Do NOT switch this to ON DELETE SET NULL: nulling superseded_by would
    -- make a replaced row start entitling again.
    superseded_by         uuid,

    -- Refund / chargeback. Written by the voided-purchase sweep (NEXT WAVE — see the known gap in
    -- the wave notes). While NULL, the row entitles until entitled_until.
    voided_at             timestamptz,

    -- A license-tester or closed-track purchase. It DOES entitle — testers must be able to exercise
    -- the paid tiers — but revenue reporting and cleanup have to be able to tell it apart.
    test_purchase         boolean       NOT NULL DEFAULT false,

    -- Latest order id, for support and for matching a voidedPurchaseNotification.
    latest_order_id       varchar(64),

    -- The out-of-order guard for THIS ROW: the eventTimeMillis of the newest notification already
    -- applied. NULLABLE, and NULL means "no notification has ever been applied to this row" — the
    -- verify endpoint MUST NOT write it, because verifying applies no notification. Seeding it with
    -- now() would put the high-water mark AHEAD of every RTDN already in flight in Pub/Sub for this
    -- purchase (including the SUBSCRIPTION_PURCHASED notification itself), and the next wave's
    -- handler would discard them all, silently and unrecoverably.
    --   bigint epoch-millis, the same unit and type as play_notifications.event_time_millis, so the
    -- comparison the guard depends on needs no conversion on either side.
    --   The handler's guard is therefore:
    --       last_event_time IS NULL OR :eventTimeMillis > last_event_time
    -- and for a token with NO row here yet (a purchase made with the app closed, a promo code
    -- redeemed in the Play Store) the watermark is
    --       SELECT max(event_time_millis) FROM play_notifications
    --        WHERE purchase_token = :token AND processed_at IS NOT NULL
    -- which ix_play_notifications_token already serves. That is the chosen mechanism; the schema
    -- provides no ordering guarantee beyond those two reads.
    last_event_time       bigint,

    -- When Google last confirmed this row. Drives the 60-second re-verify window that stops a
    -- client loop from burning the Play API quota, and the reconciler when it ships.
    verified_at           timestamptz   NOT NULL DEFAULT now(),

    -- DEFAULT now() so an operator can hand-write a row; Hibernate (AuditedEntity) always supplies
    -- both explicitly.
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now(),

    -- One row per purchase token, globally. THIS is the idempotency key: a replayed or concurrent
    -- POST of the same token loses here instead of creating a second entitlement. `ux_`, like every
    -- other unique constraint in V1-V9 — there is no `uq_` anywhere in this schema and a lone
    -- exception breaks constraint-name greps during an incident.
    CONSTRAINT ux_user_subscriptions_purchase_token UNIQUE (purchase_token),

    -- Redundant on its own (id is already the PK); it exists so the composite self-FK below can
    -- reference it. Exactly V4's ux_locations_id_space.
    CONSTRAINT ux_user_subscriptions_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_user_subscriptions_superseded_same_user
        FOREIGN KEY (superseded_by, user_id) REFERENCES user_subscriptions (id, user_id),

    -- A real purchase always has both; only a hand-written operator grant may omit them, so an
    -- operator never has to fabricate a token in Google's global namespace. (If you do write one,
    -- use the `operator:<uuid>` convention in deploy/README.md Step 10 rather than a NULL, so
    -- support can still grep for it.)
    CONSTRAINT ck_user_subscriptions_token_required
        CHECK (purchase_token IS NOT NULL OR provenance = 'OPERATOR'),
    CONSTRAINT ck_user_subscriptions_product_required
        CHECK (product_id IS NOT NULL OR provenance = 'OPERATOR'),

    -- Only purchasable tiers. FREE is not a subscription, and UNLIMITED is operator-only: making it
    -- unrepresentable here is what guarantees "granted" can always be told from "paid".
    CONSTRAINT ck_user_subscriptions_tier
        CHECK (tier IN ('STANDARD', 'PRO', 'MAX')),

    CONSTRAINT ck_user_subscriptions_provenance
        CHECK (provenance IN ('PLAY_PURCHASE', 'PROMO_CODE', 'OPERATOR')),

    -- SubscriptionState byte for byte. UNKNOWN is included because it is stored, not because it
    -- entitles.
    CONSTRAINT ck_user_subscriptions_state
        CHECK (state IN ('ACTIVE', 'CANCELED', 'IN_GRACE_PERIOD', 'ON_HOLD', 'PAUSED',
                         'EXPIRED', 'PENDING', 'PENDING_PURCHASE_CANCELED', 'UNKNOWN')),

    -- Blocks the length-1 cycle. Longer cycles are blocked by ux_user_subscriptions_supersedes
    -- below: a row may be superseded at most once, so any cycle would need a self-reference.
    CONSTRAINT ck_user_subscriptions_not_self_superseded
        CHECK (superseded_by IS NULL OR superseded_by <> id),

    CONSTRAINT ck_user_subscriptions_last_event_time
        CHECK (last_event_time IS NULL OR last_event_time > 0)
);

-- The FK cascade's index. `user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE` makes
-- PostgreSQL run `DELETE FROM user_subscriptions WHERE user_id = $1` on every DELETE /users/me,
-- and that predicate does NOT imply the partial index's WHERE clause, so without this the RI
-- trigger seq-scans the table once per account deletion. Every other users-referencing table here
-- has one (ix_spaces_user, ix_items_user, ix_refresh_tokens_user, ix_assistant_messages_user_created).
CREATE INDEX ix_user_subscriptions_user
    ON user_subscriptions (user_id);

-- The entitlement query, and the only one on the hot path. The index is (user_id, entitled_until
-- DESC) with the two STATIC predicates in the partial WHERE: it turns the entitlement lookup into
-- an index-range scan over one user's non-voided, non-superseded rows. It does not "read the newest
-- few and stop" — the query has no ORDER BY and no LIMIT and reduces in Java; the DESC ordering
-- only puts the still-entitling rows first within the user's range.
CREATE INDEX ix_user_subscriptions_entitling
    ON user_subscriptions (user_id, entitled_until DESC)
    WHERE voided_at IS NULL AND superseded_by IS NULL;

-- Two jobs in one index. (1) Referencing-side coverage for the composite self-FK — without it,
-- deleting an account seq-scans this table once per row deleted (the trap V8 documented for its
-- ON DELETE SET NULL columns). (2) UNIQUE, so a row can be superseded at most once, which makes a
-- multi-row supersession cycle unrepresentable (a cycle would need a self-reference, already
-- blocked by ck_user_subscriptions_not_self_superseded). A cycle would exclude every row in it
-- from the entitlement query forever, so a paying user would read FREE with nothing to explain it.
CREATE UNIQUE INDEX ux_user_subscriptions_supersedes
    ON user_subscriptions (superseded_by)
    WHERE superseded_by IS NOT NULL;

-- The next wave resolves an upgrade's linkedPurchaseToken to a local row; without this it is a
-- sequential scan on every upgrade notification.
CREATE INDEX ix_user_subscriptions_linked
    ON user_subscriptions (linked_purchase_token)
    WHERE linked_purchase_token IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- 3. play_notifications — the RTDN ledger.
-- ---------------------------------------------------------------------------------------------
-- The handler is OUT OF SCOPE for this wave. The table is in scope because the ordering and retry
-- guarantees have to be designed before anything writes them, and because retrofitting a dedup key
-- (or a retry counter) onto a stream that has already been consumed is not possible.
--
-- KEYED ON THE PUB/SUB MESSAGE ID. Pub/Sub guarantees at-least-once delivery, so the same message
-- arrives more than once as a matter of course. Insert-first / process-after makes a redelivery a
-- primary-key collision and therefore a no-op, with no application-level "have I seen this?" query.
--
-- event_time_millis is the ORDERING guard and is a different question from the dedup key: Pub/Sub
-- does not guarantee order, so a stale ACTIVE can arrive after a fresh EXPIRED. Stored as bigint
-- epoch-millis exactly as Google sends it, and compared against user_subscriptions.last_event_time,
-- which is the same type and unit — see that column's comment for the full guard, including the
-- watermark for a token with no local subscription row.
--
-- NO foreign key to user_subscriptions and NO user_id. A notification can legitimately arrive for
-- a token this server has never seen (a purchase made with the app closed, a promo code redeemed
-- in the Play Store). A ledger that could refuse to record what Google actually sent would be
-- useless exactly when it is needed.
CREATE TABLE play_notifications (
    message_id         varchar(128)  PRIMARY KEY,
    publish_time       timestamptz   NOT NULL,
    event_time_millis  bigint        NOT NULL,
    package_name       varchar(128)  NOT NULL,

    -- Which sibling field of the RTDN envelope was populated. voidedPurchaseNotification is a
    -- SIBLING of subscriptionNotification, not a subtype of it — a handler that only looks at
    -- subscriptionNotification silently ignores every refund. No CHECK yet, on purpose: the Java
    -- enum that would pin it ships with the handler, and a CHECK derived from nothing can only
    -- reject a real notification later.
    notification_kind  varchar(32)   NOT NULL,

    -- The numeric subtype inside that field (subscriptionNotificationType, or the voided
    -- productType). Numeric because Google's wire value is numeric; naming it is the handler's job.
    notification_type  integer,

    purchase_token     text,
    product_id         varchar(64),
    order_id           varchar(64),

    -- The decoded message verbatim. When a handler bug is found, this is what it is re-run against.
    payload            jsonb         NOT NULL,

    received_at        timestamptz   NOT NULL DEFAULT now(),

    -- Retry bookkeeping. Without these, a failing handler has only two legal moves — record the
    -- error and drop the event, or keep the retry and hide the error — and one permanently-bad
    -- payload spins forever at the head of the queue. processed_at means SUCCEEDED; a failure
    -- leaves it NULL, bumps attempts and records processing_error, so the row stays in the work
    -- queue until attempts runs out.
    attempts           integer       NOT NULL DEFAULT 0,
    last_attempt_at    timestamptz,
    processed_at       timestamptz,
    -- Short and bounded: an error string, never a stack trace, and never the purchase token.
    processing_error   varchar(500),

    CONSTRAINT ck_play_notifications_attempts CHECK (attempts >= 0),
    -- A processed (= succeeded) row carries no error; a pending row may carry the last failure.
    CONSTRAINT ck_play_notifications_error_only_while_pending
        CHECK (processed_at IS NULL OR processing_error IS NULL)
);

-- The work queue: oldest unprocessed first, poison messages excluded so they cannot block it.
-- Partial, so it stays the size of the backlog rather than the size of history.
CREATE INDEX ix_play_notifications_unprocessed
    ON play_notifications (received_at)
    WHERE processed_at IS NULL AND attempts < 10;

-- "Every event for this token, newest first" — the support question, the reconciler's input, and
-- the watermark read for a token with no user_subscriptions row yet.
CREATE INDEX ix_play_notifications_token
    ON play_notifications (purchase_token, event_time_millis DESC)
    WHERE purchase_token IS NOT NULL;
