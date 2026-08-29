CREATE TABLE items (
    id                  uuid PRIMARY KEY,
    user_id             uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    current_location_id uuid         NOT NULL REFERENCES locations (id) ON DELETE RESTRICT,
    name                varchar(120) NOT NULL,
    normalized_name     varchar(120) NOT NULL,
    description         text,
    category            varchar(100),
    archived            boolean      NOT NULL DEFAULT false,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX ix_items_user ON items (user_id);
CREATE INDEX ix_items_location ON items (current_location_id);

CREATE TABLE item_location_history (
    id                     uuid PRIMARY KEY,
    item_id                uuid        NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    -- History must survive location deletion; the snapshot keeps entries readable.
    location_id            uuid        REFERENCES locations (id) ON DELETE SET NULL,
    location_path_snapshot text        NOT NULL,
    note                   varchar(500),
    placed_at              timestamptz NOT NULL DEFAULT now(),
    removed_at             timestamptz
);

-- At most one open (current) history record per item, enforced by the database.
CREATE UNIQUE INDEX ux_history_open ON item_location_history (item_id) WHERE removed_at IS NULL;
CREATE INDEX ix_history_item ON item_location_history (item_id);
CREATE INDEX ix_history_location ON item_location_history (location_id);
