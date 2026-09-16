-- One row per assistant request (REMEMBER/SEARCH), owned by the user, kept for the life of the
-- account. The user's own sentence is DATA here, not a log line: §6 forbids logging it above DEBUG.
CREATE TABLE assistant_messages (
    id             uuid PRIMARY KEY,
    user_id        uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    mode           varchar(20)   NOT NULL,
    -- The sentence exactly as typed (post-sanitize: NFKC-cleaned, control chars stripped). 1000 is
    -- RememberRequest's cap; SEARCH's 500 is enforced in AssistantService.sanitize, not here.
    message        varchar(1000) NOT NULL,
    outcome        varchar(24)   NOT NULL,
    -- Validated-or-rejected AI output, or the SEARCH keywords. Nothing not derived from the request.
    interpretation jsonb,
    -- ErrorCode name (or UNEXPECTED_ERROR) — FAILED rows only; NULL for every other outcome.
    error_code     varchar(64),
    provider       varchar(20)   NOT NULL,
    model          varchar(100)  NOT NULL,
    prompt_version varchar(64)   NOT NULL,
    -- [0,1] at three decimals (InterpretationSnapshots.CONFIDENCE_SCALE); NULL when the provider gave none.
    confidence     numeric(4,3),
    item_id        uuid          REFERENCES items (id) ON DELETE SET NULL,
    space_id       uuid          REFERENCES spaces (id) ON DELETE SET NULL,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_assistant_messages_mode
        CHECK (mode IN ('REMEMBER', 'SEARCH')),
    CONSTRAINT ck_assistant_messages_outcome
        CHECK (outcome IN ('CREATED', 'NEEDS_CONFIRMATION', 'NOT_UNDERSTOOD', 'ANSWERED', 'FAILED')),
    CONSTRAINT ck_assistant_messages_confidence
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    -- item_id is only ever set by the CREATED path; it may later become NULL (ON DELETE SET NULL).
    CONSTRAINT ck_assistant_messages_item_only_when_created
        CHECK (item_id IS NULL OR outcome = 'CREATED'),
    CONSTRAINT ck_assistant_messages_error_code_only_when_failed
        CHECK (error_code IS NULL OR outcome = 'FAILED')
);

-- Per-user, newest first: account deletion today, a history endpoint tomorrow.
CREATE INDEX ix_assistant_messages_user_created ON assistant_messages (user_id, created_at DESC);
-- Referencing-side indexes: without them every item or space delete (including the account-deletion
-- bulk deletes) seq-scans this table for the ON DELETE SET NULL trigger.
CREATE INDEX ix_assistant_messages_item ON assistant_messages (item_id) WHERE item_id IS NOT NULL;
CREATE INDEX ix_assistant_messages_space ON assistant_messages (space_id) WHERE space_id IS NOT NULL;
