-- V12 — the internal marketplace: listings a user publishes from items they already own, and the
-- anonymous abuse report that is the only thing an unauthenticated stranger may WRITE anywhere in
-- this schema.
--
-- THE PRODUCT DECISION THIS ENCODES. A user may offer their OWN existing item for sale on an
-- internal board that ANONYMOUS, unregistered visitors browse. Every column below is therefore
-- either published to the open internet or exists to stop something being published. Three things
-- must be present before an item can be listed — a PHOTO, a PRICE and a DETAILED DESCRIPTION — and
-- all three are NOT NULL here rather than nullable-with-a-service-check: a listing row that exists
-- IS a published listing, so "publishable" is a property of the ROW EXISTING rather than of a
-- status value, and two of the three prerequisites become database invariants instead of
-- conventions a future writer can forget.
--
-- THE ITEM'S INTERNAL LOCATION PATH IS NOT REFERENCED BY ANY COLUMN IN THIS FILE, AND MUST NEVER
-- BE. There is no location_id, no space_id and no path snapshot here, and that absence IS the
-- design: a listing that carried one would be a single careless mapper away from publishing
-- "Home > Bedroom > Wardrobe" to the internet. Where a buyer can collect the item is a SELLER
-- STATEMENT (`city` below), never a derivation of the tree — deriving it would publish the space
-- name, which is the same leak by another route. An ArchUnit rule forbids ..whereis.marketplace..
-- from depending on ..whereis.location.. at all, so this is a build failure and not a comment.
--
-- WHAT THIS MIGRATION DELIBERATELY DOES NOT DO:
--   * No payment, order, escrow, fee or ledger. This is a listing board. There is deliberately no
--     column a payment feature could be bolted onto without a migration of its own.
--   * NO in-app messaging. That is a LATER WAVE, and the shape it needs (a thread keyed on
--     (listing_id, buyer) with its own retention rule and its own legal-page paragraph) is absent
--     rather than half-present.
--   * No change to users.plan, ck_users_plan or user_subscriptions, and this is the one absence a
--     reader is most likely to expect the opposite of. The THIRD plan allowance — a per-tier cap on
--     simultaneously ACTIVE listings — is CONFIGURATION (whereis.plans.<tier>.listings), exactly
--     like spaces and items, and configuration is not schema. PlanCatalog is where to look.
--   * No reporter identity of any kind on listing_reports. SEE SECTION 4: A REPORTER IP HASH WAS
--     DESIGNED AND REJECTED.
--   * No counter column on listings. An anonymous endpoint that increments a row an attacker
--     chooses is a hot-row contention lever, and the operator's question is one GROUP BY away.
--   * NO DENORMALIZED COPY OF items.archived, AND THAT WAS CONSIDERED AT LENGTH. "An ACTIVE
--     listing never points at an archived item" IS expressible purely in the database: items would
--     need UNIQUE (id, archived), listings a nullable `item_archived boolean` forced false while
--     ACTIVE and NULL once ended (MATCH SIMPLE skips the check when any referencing column is
--     NULL), a composite FK (item_id, item_archived) -> items (id, archived) with ON UPDATE NO
--     ACTION, and two more CHECKs binding the copy to the status. It works, and it is rejected: it
--     buys a BACKSTOP for a rule with exactly one writer, it makes every status transition
--     responsible for maintaining a copy of somebody else's mutable flag, and the failure it
--     produces is a 23503 at end of statement that the service still has to pre-empt to answer
--     anything but a 500. The rule lives in ItemService.update. Recorded so nobody re-derives it
--     and concludes it was overlooked.
--   * No UPDATE and no backfill. There are no rows: the feature does not exist yet.
--   * No char(N) anywhere (Hibernate 6.6 validate treats bpchar as a type mismatch and the app
--     refuses to boot), and no PostgreSQL native enums. Every enum-ish value is varchar + a NAMED
--     CHECK written as an IN list — even the one with a single element — matching a Java enum byte
--     for byte, each pinned by a test that replays ADDs and DROPs across EVERY migration rather
--     than regexing one file.

-- ---------------------------------------------------------------------------------------------
-- 1. Two UNIQUE constraints that constrain nothing new, so that two COMPOSITE foreign keys can
--    exist. Exactly V4's ux_locations_id_space and V10's ux_user_subscriptions_id_user.
-- ---------------------------------------------------------------------------------------------
-- `id` is already the primary key of each table. These exist only because PostgreSQL will let a
-- foreign key reference a UNIQUE-or-PK column LIST and nothing else, and the two guarantees this
-- feature needs most are both "same owner" guarantees:
--
--   (a) a listing's user_id IS the owner of the listing's item. user_id is denormalized here on
--       purpose — the per-tier cap counts it, every scoped finder uses it, and joining through
--       items on every publish and every browse row would be the wrong trade — and this constraint
--       is what makes the denormalization non-driftable rather than a convention.
--
--   (b) a listing's cover photo is a photo OF THAT ITEM. Combined with (a), the cover is
--       transitively the SELLER'S OWN file. This is the security-critical one: the cover is
--       presigned and served to ANONYMOUS visitors, so a service bug that let cover_file_id point
--       at another user's item_files row would publish a stranger's private photograph to the open
--       internet. An IDOR with that blast radius is worth a redundant unique index.
--
-- Both take an ACCESS EXCLUSIVE lock and build an index. `items` holds 19 rows in production and
-- `item_files` 38, so both are instant TODAY — which is the argument for doing it in this
-- migration rather than after the tables have grown.
ALTER TABLE items
    ADD CONSTRAINT ux_items_id_user UNIQUE (id, user_id);

ALTER TABLE item_files
    ADD CONSTRAINT ux_item_files_id_item UNIQUE (id, item_id);

-- ---------------------------------------------------------------------------------------------
-- 2. item_files.published_object_key — the PUBLIC copy of a listing's cover photo.
-- ---------------------------------------------------------------------------------------------
-- The private object key is `u/{userId}/i/{itemId}/{fileId}`, and a presigned URL carries the key
-- in its PATH. Serving the private object to the board would therefore publish the seller's user
-- UUID and the item's UUID to every anonymous visitor and every crawler — a stable correlation key
-- that lets a scraper cluster every listing to one person, which is precisely what leaving sellerId
-- out of the public DTO is meant to prevent. Publishing instead copies the object, server-side,
-- to an OPAQUE key (`p/{random uuid}` — no user id, no item id, no file id, no listing id, no
-- structure at all) and records it here.
--
-- Nullable: only a published cover has one. UNIQUE because it is an object key, exactly like
-- object_key. No index beyond that UNIQUE: the two queries that read it are "this file's copy"
-- (by primary key) and the deletion outbox's INSERT ... SELECT (a scan of one item's files).
ALTER TABLE item_files
    ADD COLUMN published_object_key text;

ALTER TABLE item_files
    ADD CONSTRAINT ux_item_files_published_key UNIQUE (published_object_key);

-- ---------------------------------------------------------------------------------------------
-- 3. listings — one published offer.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE listings (
    -- DEFAULT so an operator can hand-write a row during an incident; Hibernate always supplies
    -- its own via @UuidGenerator.
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),

    -- The house shape for an owned aggregate, and the backstop cascade. It is also what
    -- ix_listings_user (below) serves on DELETE /users/me. Kept ALONGSIDE the composite FK to
    -- items rather than replaced by it: if items ever loses ux_items_id_user the composite FK must
    -- go, and this is what keeps the users cascade correct regardless.
    user_id         uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- The item being sold. No standalone FK: the composite one below carries the reference AND the
    -- same-owner guarantee in a single constraint, so there is exactly one answer to "what happens
    -- to a listing when the item goes".
    item_id         uuid          NOT NULL,

    -- ACTIVE | SOLD | WITHDRAWN, and BOTH terminal states are terminal on purpose. A listing is
    -- never re-activated; re-listing INSERTs a new row. The reason is listing_reports: reopening a
    -- row would let a report filed against one price and one description silently apply to another,
    -- and the report is the only record of what was actually published. Same reasoning as
    -- item_location_history.location_path_snapshot — the artefact a complaint names must not be
    -- rewritable. varchar(20) to match users.plan / user_subscriptions.tier rather than shaving
    -- four bytes off the longest value.
    status          varchar(20)   NOT NULL,

    -- When it stopped being ACTIVE. ONE column, not sold_at + withdrawn_at: `status` already says
    -- WHY, and two columns would let both be set, which means nothing. NULL exactly while ACTIVE —
    -- the paired CHECK below is the invariant BETWEEN the two columns, and is a different statement
    -- from the enum pin, which is why they are separate constraints.
    ended_at        timestamptz,

    -- THE OPERATOR KILL-SWITCH, and deliberately NOT a fourth status value. The operator's decision
    -- and the seller's decision are INDEPENDENT FACTS: as a status, an operator hiding an ACTIVE
    -- listing would overwrite ACTIVE, and the seller then marking it sold would silently un-hide
    -- it. As a separate nullable timestamp both are true at once, neither writer can erase the
    -- other's, and the hide has a WHEN. This is V10/V11's voided_at-versus-state lesson applied
    -- unchanged; @DynamicUpdate on the entity is what stops a seller's full-column UPDATE writing
    -- NULL back here from a stale snapshot, for the same reason it is load-bearing on
    -- user_subscriptions.
    hidden_at       timestamptz,
    -- Why. Coarse enough to show the SELLER (hiding something without telling them is how a user
    -- concludes the app is broken) and pinned to a Java enum so the copy can be translated.
    hidden_reason   varchar(32),
    -- The operator's own note. Untranslated, bounded at 500 characters, and NEVER on any wire —
    -- not the public response and not the seller's.
    hidden_note     varchar(500),

    -- THE LISTING'S OWN SELLING TEXT, not items.name / items.description, and this is the decision
    -- with the most consequences downstream. Three reasons, in ascending order of how hard they are
    -- to argue with:
    --   (1) audience — the item fields answer "what is this thing so I recognise it in my own
    --       list"; these answer "why should a stranger buy it";
    --   (2) privacy — items.description is a PRIVATE note the user has been writing for a year.
    --       Re-purposing a private field as public copy is the same class of mistake as publishing
    --       the location path;
    --   (3) the one that settles it — UpdateItemRequest.description is applied UNCONDITIONALLY by
    --       ItemService.update, so a PUT /items/{id} that omits the field BLANKS it. A listing
    --       whose public text depended on that column would be silently emptied by a routine
    --       rename from the already-shipped Android build, while still ACTIVE and still public.
    -- The client pre-fills both from the item; the server copies nothing implicitly.
    title           varchar(120)  NOT NULL,
    description     text          NOT NULL,

    -- numeric, never float: this is money. NOT NULL is half of "a price must be present"; the other
    -- half is ck_listings_price_amount, which refuses 0 — a zero price is a giveaway, which is a
    -- different product, and "0, call me" is the standard way to dodge a price field entirely.
    -- The ENTITY declares no precision and no scale (assistant_messages.confidence's lesson): a
    -- precision that disagrees with this column stops the application booting under validate.
    -- Scale 2 means PostgreSQL would silently ROUND 10.999 to 11.00, so the service rejects more
    -- than two decimal places rather than letting a listing be priced differently from what the
    -- seller typed.
    price_amount    numeric(12,2) NOT NULL,

    -- ONE currency today, stored explicitly anyway. A price with no currency on the row is a number
    -- whose meaning lives in a comment, and the day a second currency exists every pre-existing row
    -- is ambiguous and cannot be backfilled with certainty. A one-value CHECK costs one line and is
    -- widened by one line. Exactly play_cancellation_queue.reason, which this schema already ships
    -- with the same justification.
    price_currency  varchar(3)    NOT NULL DEFAULT 'AZN',

    -- PUBLISHED TO ANONYMOUS VISITORS. Deliberately not on `users`: a phone number on the account
    -- would be collected from 100% of users — changing the privacy notice and the Play Data-safety
    -- declaration for everyone — to serve a feature used by few, and it would fix one number per
    -- person when a seller may legitimately want a work number on the drill and a personal one on
    -- the sofa. Stored NORMALISED ('+' then digits) rather than as typed, because this value is
    -- dialled, not read. 32 rather than 16 so a later ';ext=' needs no ALTER.
    contact_phone   varchar(32)   NOT NULL,

    -- WHERE A BUYER CAN COLLECT IT — a seller statement, free text, and the ONLY location fact this
    -- table holds. It does not follow the item: moving the item to an office in another city leaves
    -- this stale until the seller edits it, and that is correct, because deriving it from the tree
    -- would publish the space name. normalized_city is Names.normalize (the Azerbaijani diacritic
    -- fold, so "Bakı"/"baki"/"BAKI" are one filter key) and is what the browse filter and its index
    -- use; `city` is Names.clean and is what a visitor sees, unflattened. The same split as
    -- spaces.name/normalized_name and items.name/normalized_name, for the same reason.
    city            varchar(80)   NOT NULL,
    normalized_city varchar(80)   NOT NULL,

    -- THE PHOTO PREREQUISITE, AS A FOREIGN KEY. A listing cannot exist without one; the service
    -- gives the good message and this is what makes a future admin script, import or second writer
    -- unable to publish a photoless listing anyway. It names ONE photo rather than relying on "the
    -- item has at least one", because (a) a constraint beats a convention and (b) the marketplace
    -- cover is a SELLING decision while item_files.is_primary is a recognise-it-in-my-own-list
    -- decision. A listing_photos join table for 2..N is the obvious next wave; this column stays as
    -- the first of them.
    cover_file_id   uuid          NOT NULL,

    -- created_at IS the publication time. There is no published_at, because a listing is published
    -- by being INSERTed and can never be re-activated — a second column would be two statements of
    -- one fact that can disagree. It is also the browse ordering key.
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),

    -- ON DELETE CASCADE, and the listing goes when the item goes. NOT refused: DELETE /items/{id}
    -- is the user acting on their own data, the Play-mandated deletion story leans on that always
    -- working, and a listing must never become a lock on a person's own inventory.
    -- The SAME-USER half is the point (see section 1): user_id cannot drift from items.user_id.
    CONSTRAINT fk_listings_item_same_user
        FOREIGN KEY (item_id, user_id) REFERENCES items (id, user_id) ON DELETE CASCADE,

    -- NO ACTION, NOT RESTRICT, AND THE DIFFERENCE IS LOAD-BEARING. Deleting an item fires two
    -- cascades from ONE statement — item_files and listings — and RESTRICT is checked IMMEDIATELY,
    -- so whichever cascade PostgreSQL runs first could abort the whole delete. NO ACTION is checked
    -- at end of statement, by which time the listing row is gone too. This is exactly why
    -- fk_locations_parent_same_space is NO ACTION, and it is also what makes DELETE /users/me work
    -- for a seller. Deleting a LIVE listing's cover on its own is still refused by this constraint
    -- (at end of that statement); FileStorageService.delete pre-empts it with 409 ITEM_LISTED so
    -- the user gets a sentence instead of a 500.
    --
    -- The (..., item_id) half guarantees the published photo belongs to the published item.
    CONSTRAINT fk_listings_cover_file_same_item
        FOREIGN KEY (cover_file_id, item_id) REFERENCES item_files (id, item_id),

    -- ListingStatus byte for byte.
    CONSTRAINT ck_listings_status
        CHECK (status IN ('ACTIVE', 'SOLD', 'WITHDRAWN')),

    -- The invariant BETWEEN status and ended_at — a different statement from the enum pin, which is
    -- why it is a second constraint.
    CONSTRAINT ck_listings_ended_at_matches_status
        CHECK ((status = 'ACTIVE') = (ended_at IS NULL)),
    CONSTRAINT ck_listings_ended_at_after_created
        CHECK (ended_at IS NULL OR ended_at >= created_at),

    -- ListingHiddenReason byte for byte.
    CONSTRAINT ck_listings_hidden_reason
        CHECK (hidden_reason IN ('PROHIBITED_ITEM', 'SCAM_OR_FRAUD', 'OFFENSIVE_CONTENT',
                                 'WRONG_OR_MISLEADING', 'ABUSE_REPORTS', 'OTHER')),

    -- ...and hiding is atomic: an operator's UPDATE cannot set the timestamp without the reason, or
    -- clear one and leave the other. hidden_note is deliberately NOT in this pair — a hide with no
    -- note is legitimate.
    CONSTRAINT ck_listings_hidden_pair
        CHECK ((hidden_at IS NULL) = (hidden_reason IS NULL)),

    -- THE "DETAILED DESCRIPTION" RULE, AS A FLOOR. 40 characters, counted in CHARACTERS
    -- (char_length counts code points, and the service counts code points too — String.length()
    -- would accept 39 emoji as 78 and then hit this constraint as a 500). The exact product number
    -- lives in Java (MarketplaceRules.MIN_DESCRIPTION_LENGTH) and a test parses this CHECK to
    -- assert the two agree; what lives HERE is the floor no writer may go under, because lowering
    -- it changes what the PUBLIC board admits and that should cost a migration. 4000 is the
    -- ceiling: `text` has none, and an unbounded field published to anonymous visitors is a storage
    -- and rendering hazard. Twice items.description's 2000 — a listing may say twice as much as a
    -- private note.
    CONSTRAINT ck_listings_description_length
        CHECK (char_length(description) BETWEEN 40 AND 4000),

    CONSTRAINT ck_listings_title_length
        CHECK (char_length(title) BETWEEN 3 AND 120),

    -- Names.clean can turn whitespace into an empty string; @NotBlank runs BEFORE cleaning. This is
    -- the backstop for a published field.
    CONSTRAINT ck_listings_city_length
        CHECK (char_length(btrim(city)) >= 2 AND char_length(btrim(normalized_city)) >= 2),

    -- > 0 rather than >= 0: see price_amount's comment. The upper bound is not what stops a numeric
    -- overflow (that would be 22003, a 500) — the request DTO's @DecimalMax is. This is the
    -- backstop, and the documentation of what a second-hand board is for.
    CONSTRAINT ck_listings_price_amount
        CHECK (price_amount > 0 AND price_amount <= 10000000.00),

    -- ListingCurrency byte for byte. Written as a one-element IN list rather than `= 'AZN'` on
    -- purpose: one test helper then reads every enum CHECK in this schema, including this one.
    CONSTRAINT ck_listings_price_currency
        CHECK (price_currency IN ('AZN')),

    -- A DATABASE-LEVEL SHAPE GUARANTEE ON A PUBLISHED FIELD, which is unusual in this schema and
    -- deliberate here: contact_phone is the one column a stranger acts on, and this is what stops a
    -- future writer that is not ListingService — an admin fix, an import, a support script —
    -- publishing something that is not a phone number. 15 digits is E.164's ceiling; 7 is a floor
    -- that excludes short codes.
    CONSTRAINT ck_listings_contact_phone
        CHECK (contact_phone ~ '^\+?[0-9]{7,15}$')
);

-- AT MOST ONE ACTIVE LISTING PER ITEM. Partial, so SOLD and WITHDRAWN history accumulates freely
-- and only the live board is constrained.
--
-- READ THE PREDICATE CAREFULLY: it is `status = 'ACTIVE'` and NOT `status = 'ACTIVE' AND hidden_at
-- IS NULL`. A HIDDEN LISTING STILL OCCUPIES ITS ITEM'S ONE SLOT, and that is the whole point — if
-- hiding freed the slot, the seller re-publishes the same thing seconds later and the operator is
-- playing whack-a-mole against an endpoint. Widening this predicate is the single easiest way to
-- destroy the kill-switch, which is why a test asserts it directly rather than leaving it to this
-- comment.
CREATE UNIQUE INDEX ux_listings_item_active
    ON listings (item_id) WHERE status = 'ACTIVE';

-- THE ANONYMOUS BOARD. `where status = 'ACTIVE' and hidden_at is null order by created_at desc` —
-- the only query in this system an unauthenticated stranger can run, and therefore the only one
-- whose cost is not bounded by an account. Partial, so it stays the size of the LIVE board rather
-- than of all history.
CREATE INDEX ix_listings_browse
    ON listings (created_at DESC) WHERE status = 'ACTIVE' AND hidden_at IS NULL;

-- The same board narrowed to one city. A SECOND index rather than a wider one, because the
-- unfiltered board cannot use a city-leading index to supply its ordering at all — V11's
-- ix_user_subscriptions_reconcile lesson, where an index on the wrong leading column left a sort
-- node behind and a review caught it.
CREATE INDEX ix_listings_browse_city
    ON listings (normalized_city, created_at DESC) WHERE status = 'ACTIVE' AND hidden_at IS NULL;

-- Two jobs, one index, exactly like ix_assistant_messages_user_created. (1) "My listings", every
-- status, newest first — the seller's own screen. (2) The referencing-side index for
-- fk_listings_user: without it every DELETE /users/me seq-scans this table for the RI trigger.
-- It ALSO serves the per-tier cap count (`user_id = ? and status = 'ACTIVE'`), and that is why
-- there is deliberately no separate partial index for the cap: the cap itself keeps the per-user
-- row count in the tens. Add one the day a tier allows hundreds.
CREATE INDEX ix_listings_user
    ON listings (user_id, created_at DESC);

-- Referencing side of fk_listings_item_same_user. The RI probe is `item_id = $1 and user_id = $2`,
-- so the leading column matches and the second predicate is a cheap filter. ux_listings_item_active
-- cannot serve it: it is PARTIAL, and a SOLD row is invisible to it. This trigger fires on every
-- DELETE /items/{id} and once per row of the account-deletion bulk item delete — the exact trap V8
-- documented for its own referencing columns.
CREATE INDEX ix_listings_item
    ON listings (item_id);

-- Referencing side of fk_listings_cover_file_same_item, for the same reason: every photo delete and
-- every item_files cascade probes this table.
CREATE INDEX ix_listings_cover_file
    ON listings (cover_file_id);

-- ---------------------------------------------------------------------------------------------
-- 4. listing_reports — what an anonymous stranger may write, and nothing else.
-- ---------------------------------------------------------------------------------------------
-- A SECOND TABLE RATHER THAN A COUNTER, and the reason is containment as much as normalisation:
-- the abuse endpoint is the only unauthenticated WRITE in this application, so it gets exactly one
-- INSERT target and no UPDATE capability anywhere in the schema. A counter on `listings` would give
-- it the ability to write a listing row, on a row an attacker chooses, with the contention that
-- implies. The operator's question — "which listings are being reported" — is one GROUP BY.
--
-- NOTHING IS STORED ABOUT THE REPORTER. A SALTED SHA-256 OF THE REPORTER'S IP WAS DESIGNED AND
-- REJECTED. It would have been the only column in this whole feature that collects a new category
-- of data about a person who is not a user of this application, which means a new paragraph on
-- /legal/privacy in BOTH languages, a matching Data-safety answer, and a LegalPagesIT test — a
-- disproportionate legal surface for a board with a handful of listings. The rate limit that
-- actually matters is per-IP and lives IN MEMORY (MarketBoardRateLimitFilter), storing nothing, and
-- the per-listing daily cap the service applies needs no identity at all — it reads
-- ix_listing_reports_listing_reported below. A reporter column is one V13 plus one page change
-- away if the board ever needs it.
CREATE TABLE listing_reports (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- ON DELETE CASCADE, AND THAT IS A RETENTION DECISION, NOT AN FK DEFAULT. It means a seller who
    -- deletes the reported item destroys the reports against it. That IS a moderation hole and it
    -- is accepted: /legal/delete-account promises a hard delete, and a surviving report row
    -- containing a snapshot of a deleted account's listing text and phone number would make that
    -- page false — the exact class of defect the 2026-09-20 audit existed to fix. A moderation
    -- ledger that outlives the account is a retention decision that must be made WITH the public
    -- pages, not smuggled in through an ON DELETE clause.
    listing_id     uuid        NOT NULL REFERENCES listings (id) ON DELETE CASCADE,

    -- ListingReportReason byte for byte.
    reason         varchar(32) NOT NULL,

    -- The reporter's own words. Untrusted anonymous input that an OPERATOR reads; never rendered
    -- anywhere public, in any response, ever.
    note           varchar(500),

    reported_at    timestamptz NOT NULL DEFAULT now(),

    -- The operator's disposition. Two columns rather than a boolean — "handled" and "what was
    -- decided" are different questions — and without them the operator's own queue query is useless
    -- after the first incident, because every report they have already judged surfaces again
    -- forever.
    reviewed_at    timestamptz,
    review_outcome varchar(24),

    CONSTRAINT ck_listing_reports_reason
        CHECK (reason IN ('PROHIBITED_ITEM', 'SCAM_OR_FRAUD', 'OFFENSIVE_CONTENT',
                          'WRONG_OR_MISLEADING', 'OTHER')),
    CONSTRAINT ck_listing_reports_outcome
        CHECK (review_outcome IS NULL OR review_outcome IN ('UPHELD', 'DISMISSED')),
    -- The invariant BETWEEN them: a reviewed report has an outcome and an outcome implies a review.
    CONSTRAINT ck_listing_reports_reviewed_pair
        CHECK ((reviewed_at IS NULL) = (review_outcome IS NULL))
);

-- Three jobs, one index. (1) Referencing side of the FK — every listing delete, and therefore every
-- item delete and every account deletion, probes this table. (2) "Every report against this
-- listing, newest first" — the operator's question. (3) The per-listing 24-hour cap the service
-- applies instead of storing a reporter identity. Leading column is listing_id so (1) works.
CREATE INDEX ix_listing_reports_listing_reported
    ON listing_reports (listing_id, reported_at DESC);

-- The operator's queue: unreviewed, oldest first. Partial, so it stays the size of the BACKLOG
-- rather than of history — ix_play_notifications_unprocessed's shape, for the same reason.
CREATE INDEX ix_listing_reports_pending
    ON listing_reports (reported_at) WHERE reviewed_at IS NULL;
