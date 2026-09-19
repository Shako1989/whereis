-- Free-tier entitlement. Two states only: FREE (1 space, 100 active items — the numbers live in
-- whereis.limits.free.*, not here) and UNLIMITED.
--
-- varchar + CHECK, never a PostgreSQL native enum: the value set has to stay editable by a plain
-- migration, and Hibernate writes the Java constant names into a varchar column. Plan.java must
-- match this CHECK byte for byte (PlanTest parses this file).
ALTER TABLE users
    ADD COLUMN plan varchar(20) NOT NULL DEFAULT 'FREE';

ALTER TABLE users
    ADD CONSTRAINT ck_users_plan CHECK (plan IN ('FREE', 'UNLIMITED'));

-- THERE IS DELIBERATELY NO `UPDATE users SET plan = ...` HERE, and its absence is the design, not
-- an omission.
--
-- Every existing row — including the one production account — starts FREE. Nothing is
-- grandfathered. UNLIMITED is a GRANT an operator performs for a specific account (internal users,
-- testers, acquaintances), not a historical fact about when the account was created: a migration
-- that stamped the existing rows would encode a one-time event in the schema and still could not
-- express "these four testers unlimited, those eight limited".
--
-- Granting is therefore an ordinary operator action, documented in deploy/README.md:
--     UPDATE users SET plan = 'UNLIMITED' WHERE lower(email) = lower('someone@example.com');
--
-- Known and accepted consequence: existing data is untouched (the production account keeps its 4
-- spaces and 19 active items), but until it is granted it cannot CREATE a 5th space. Only creation
-- is refused; nothing is deleted, hidden or archived by this migration.
--
-- This column is written by this migration's DEFAULT or by an operator, and by NOTHING else. When
-- billing arrives, subscription state gets its own table: an RTDN expiry writing UNLIMITED back to
-- FREE here would silently erase a hand-made grant. See PlanLimitEnforcer#hasUnlimitedEntitlement.
