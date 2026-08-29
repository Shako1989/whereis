CREATE TABLE users (
    id            uuid PRIMARY KEY,
    email         varchar(320) NOT NULL,
    password_hash varchar(100) NOT NULL,
    first_name    varchar(100),
    last_name     varchar(100),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

-- Emails are stored normalized (lower-case); the expression index is the backstop.
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- varchar, not char: Hibernate's ddl-auto=validate treats bpchar as a type mismatch,
    -- and SHA-256 hex is always exactly 64 chars anyway.
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
