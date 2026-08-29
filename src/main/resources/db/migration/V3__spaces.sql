CREATE TABLE spaces (
    id              uuid PRIMARY KEY,
    user_id         uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name            varchar(80)  NOT NULL,
    normalized_name varchar(80)  NOT NULL,
    description     varchar(500),
    type            varchar(20)  NOT NULL
        CHECK (type IN ('HOME', 'OFFICE', 'CAR', 'GARAGE', 'WAREHOUSE', 'OTHER')),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ux_spaces_user_name UNIQUE (user_id, normalized_name)
);

CREATE INDEX ix_spaces_user ON spaces (user_id);
