-- V13 — seller-level moderation: the one row that takes an ACCOUNT off the public board.
--
-- WHAT V12 COULD NOT DO. Its kill-switch (`listings.hidden_at` + `hidden_reason`) acts on ONE ROW.
-- A scammer posting ten listings costs ten operations and nothing at all stops the eleventh, because
-- every control in V12 is keyed on a listing id. Google Play's user-generated-content policy asks
-- for more than content removal from an app hosting a public board: it asks for a way to BLOCK A
-- USER. That is the gap this migration closes, and it is the gap that gets an app removed.
--
-- A MARKETPLACE SANCTION, NOT AN ACCOUNT DELETION, AND THE ASYMMETRY IS THE WHOLE POINT. A blocked
-- seller keeps every item, space, location, photo and history row they own, keeps reading them, and
-- keeps using the private application exactly as before. Nothing in `items`, `spaces`, `locations`
-- or `item_files` is referenced by this file, let alone written by it. What they lose is the
-- PUBLISHING PRIVILEGE — business rule 7's one exception to business rule 2, withdrawn from one
-- account.
--
-- WHAT THIS MIGRATION DELIBERATELY DOES NOT DO:
--   * NO COLUMN ON `users`, AND THAT IS THE DECISION A READER IS MOST LIKELY TO EXPECT THE OPPOSITE
--     OF, because the block IS one-per-account where V12's `contact_phone` was one-per-listing.
--     Half of V12's argument for keeping the phone off `users` therefore does NOT apply here, and
--     the other half applies with more force. Three reasons:
--       (1) THE ANONYMOUS QUERY MUST NEVER BE ABLE TO NAME `users`. The board's visibility predicate
--           has to consult this fact on every request. As a column on `users` that predicate reads
--           `... AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = l.user_id AND u.blocked_at IS
--           NOT NULL)`, which puts the table holding every e-mail and every bcrypt hash into the one
--           query in this application an unauthenticated stranger can run. V12 made "the board's
--           FROM clause is ONE table" structural precisely so a leak could not be expressed; the
--           anti-join target below is a table whose every column is already an operator's own note
--           about a sanction, so the worst a careless `SELECT b.*` can publish is the moderator's
--           e-mail — bad, but not the password file. This is the argument that settles it.
--       (2) `users` IS MAPPED BY `User`, WHICH EVERY FEATURE ALREADY LOADS. Four moderation columns
--           there are four fields on an entity that auth, plan reporting and account deletion all
--           hold managed instances of — and V12 already records what a full-column UPDATE from a
--           stale snapshot does to a nullable operator timestamp (`@DynamicUpdate` on `Listing` is
--           load-bearing for exactly that reason). A separate table cannot be written by a writer
--           that does not know it exists.
--       (3) A ROW IS A CLEANER BOOLEAN THAN FOUR NULLABLE COLUMNS. `user_id` is the PRIMARY KEY, so
--           blocking twice is one row rather than two that could later disagree, unblocking is one
--           DELETE with no half-cleared state to get wrong, and there is no state machine and no
--           pair CHECK to forget. On `users` every one of those four columns is NULL for 100% of
--           accounts forever.
--   * NO LEDGER OF PAST BLOCKS. There is no `unblocked_at`, so this table holds CURRENT sanctions
--     only and an unblock DELETEs the row. An append-only history (id PK, `user_id` unique WHERE
--     `unblocked_at IS NULL` — `item_location_history`'s shape) was designed and rejected: it is a
--     NEW RETENTION SURFACE, and `listing_reports`' ON DELETE CASCADE already records this project's
--     position that "a moderation ledger that outlives the thing it describes is a retention
--     decision that must be made WITH the public pages, not smuggled in through an ON DELETE
--     clause". The block's own audit (who, when, why) lives in the row for as long as the sanction
--     does, and `SellerBlockService` logs the removed row's four facts at INFO on the way out, which
--     is the same record every other operator action in this deployment leaves.
--   * NO CHANGE TO `listings`. A block is applied by FILTERING the board, never by stamping
--     `hidden_at` onto the seller's rows. See section 2.
--   * NO DENORMALIZED `listings.seller_blocked` FLAG folded into the two browse indexes. It would
--     make the board's cost identical to V12's, and it is rejected for the reasons V12 already wrote
--     down when it rejected the same shape for `items.archived`: it buys a BACKSTOP for a rule with
--     one writer, it makes every future listing writer responsible for maintaining a copy of
--     somebody else's mutable flag, and — the new reason — it could not close the race the filter
--     closes (a publish that passed the guard microseconds before the block commits would insert an
--     un-flagged ACTIVE row and put it on the board).
--   * NO IDENTITY BAN. The key is a `users.id`, so a blocked seller who exercises the Play-mandated
--     DELETE /users/me and registers again is a new account with no sanction. Closing that would
--     mean retaining something about a deleted person, which is exactly what /legal/delete-account
--     promises not to do. Recorded so nobody concludes it was overlooked.
--   * No `char(N)` anywhere, no PostgreSQL native enum: varchar + a NAMED CHECK written as an IN
--     list matching a Java enum byte for byte, pinned by `ListingEnumsTest` through the same
--     ADD/DROP replay every other enum in this schema uses.

-- ---------------------------------------------------------------------------------------------
-- 1. blocked_sellers — one row per account currently barred from the public board.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE blocked_sellers (
    -- THE USER ID IS THE PRIMARY KEY. Blocking an already-blocked seller is an UPSERT of one row,
    -- not a second row, so "is this account blocked" has exactly one answer and needs no ordering.
    --
    -- ON DELETE CASCADE, not RESTRICT: DELETE /users/me is mandated by Play and must never be
    -- blockable by a sanction. A dangling row would make the deletion cascade fail for precisely
    -- the accounts most likely to use it. The block has nothing left to hide once the account (and
    -- with it every listing) is gone, so there is nothing to preserve.
    --
    -- The cascade needs no index of its own: this is the referencing side and `user_id` IS the PK.
    user_id    uuid         PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,

    -- WHEN. `now()` as a DEFAULT so an operator can still hand-write a row during an incident;
    -- the application always supplies its own.
    blocked_at timestamptz  NOT NULL DEFAULT now(),

    -- WHY, as an enum rather than free text — the opposite of the parked draft this feature was
    -- prototyped in, and deliberately. The reason is SHOWN TO THE SELLER (a listing that vanished
    -- without explanation is how a user concludes the application is broken), so it has to be
    -- translatable, which means a closed set. `SellerBlockReason` byte for byte; the operator's own
    -- untranslated words go in `note`. Exactly the `hidden_reason` / `hidden_note` split V12 chose
    -- for the same two audiences.
    reason     varchar(32)  NOT NULL,

    -- The operator's note for a colleague. Bounded, internal, and NEVER on any wire — not the
    -- public board, not the seller's own response. `hidden_note`'s twin.
    note       varchar(500),

    -- WHO. The moderator's e-mail, stored `Names.normalize`d because that is the form `users.email`
    -- itself is stored in and the form the allowlist is compared in — one normalization, or the
    -- audit column and the authorization check disagree about who acted. 320 is RFC 5321's ceiling
    -- and `users.email`'s width.
    --
    -- NOT a `users(id)` FK: the moderator may legitimately be an operator whose own account is
    -- later deleted, and an audit column that CASCADEs away is not an audit column. The e-mail is a
    -- snapshot of who decided, in the same spirit as `item_location_history.location_path_snapshot`.
    blocked_by varchar(320) NOT NULL,

    -- Every fact on this row is NOT NULL, so unlike `listings.hidden_at`/`hidden_reason` there is no
    -- pair CHECK to write: the ROW EXISTING is the block, and a block with no reason or no author
    -- cannot be represented at all.
    --
    -- `SellerBlockReason` byte for byte.
    CONSTRAINT ck_blocked_sellers_reason
        CHECK (reason IN ('SCAM_OR_FRAUD', 'PROHIBITED_ITEMS', 'OFFENSIVE_CONTENT',
                          'SPAM_OR_BULK_LISTINGS', 'REPEATED_VIOLATIONS', 'OTHER')),

    -- Names.clean can reduce whitespace to nothing and @NotBlank runs BEFORE cleaning. The backstop
    -- for the one column that answers "who did this".
    CONSTRAINT ck_blocked_sellers_blocked_by
        CHECK (char_length(btrim(blocked_by)) >= 3)
);

-- ---------------------------------------------------------------------------------------------
-- 2. NO INDEX, AND NO CHANGE TO THE TWO BOARD INDEXES. Both absences are load-bearing.
-- ---------------------------------------------------------------------------------------------
-- The board's predicate gains one clause and nothing else:
--
--   status = 'ACTIVE' AND hidden_at IS NULL
--     AND NOT EXISTS (SELECT 1 FROM blocked_sellers b WHERE b.user_id = l.user_id)
--
-- NOT EXISTS rather than `user_id NOT IN (SELECT ...)`: both are correct here (the subquery column
-- is a NOT NULL primary key, so the NOT IN three-valued-logic trap cannot fire), but the trap is one
-- nullable column away at all times and the anti-join is what the planner wants either way.
--
-- `ix_listings_browse` and `ix_listings_browse_city` STILL SERVE THE QUERY UNCHANGED, and that is
-- the reason this clause is a subquery instead of a column. Their partial predicates
-- (`status = 'ACTIVE' AND hidden_at IS NULL`) are untouched, so they still supply both the
-- visibility filter and the `created_at DESC` ordering; the anti-join is an extra node ABOVE the
-- index scan, probing this table's primary key once per candidate row. A partial index could not
-- have expressed the new clause anyway — an index predicate cannot reference another table.
--
-- MEASURED, not assumed. PostgreSQL 16, 20,000 listings across 2,000 sellers, 50 of them blocked,
-- EXPLAIN (ANALYZE, BUFFERS):
--
--   unfiltered first page   before: Index Scan ix_listings_browse, 33 buffers, 0.26 ms
--                            after: same Index Scan + Nested Loop Anti Join whose inner side is an
--                                   Index Only Scan on blocked_sellers_pkey (Heap Fetches: 0),
--                                   46 buffers, 0.11 ms  ->  +13 cached index pages
--   ?city=baki               after: Index Scan ix_listings_browse_city WITH its Index Cond, so the
--                                   second index is still doing both of its jobs
--   blocked_sellers EMPTY    after: the planner drops to a Seq Scan over ZERO rows — the day-one
--                                   state, and the clause costs nothing at all
--   half of all sellers      after: 129 buffers, 0.11 ms; the index scan examines 42 rows to
--                                   return 21, i.e. the cost degrades linearly in the blocked
--                                   FRACTION and not in the table size
--   deepest legal page       before: 5,016 buffers, 1.18 ms
--   (page 99 of 50)           after: 10,291 buffers, 4.01 ms  ->  ROUGHLY DOUBLE
--
-- That last line is the one worth knowing: the anti-join probes once per row EXAMINED, and a deep
-- OFFSET examines 5,000 of them to return 51. It is not a new problem — the deep page was already
-- the expensive shape, which is why MarketBoardService caps `page` at 99 — but it is now twice as
-- expensive, and if the board ever needs deeper paging the fix is keyset pagination on
-- (created_at, id) rather than a flag on `listings`.
--
-- There is deliberately no second index on `blocked_sellers`: it has exactly one access pattern
-- ("is this user id in here"), and the primary key is that access pattern.
