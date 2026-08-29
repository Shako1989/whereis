CREATE TABLE locations (
    id                 uuid PRIMARY KEY,
    space_id           uuid         NOT NULL REFERENCES spaces (id),
    parent_location_id uuid,
    name               varchar(80)  NOT NULL,
    normalized_name    varchar(80)  NOT NULL,
    description        varchar(500),
    type               varchar(20)  NOT NULL
        CHECK (type IN ('ROOM', 'FURNITURE', 'CABINET', 'DRAWER', 'SHELF', 'BOX', 'DESK', 'BAG', 'CONTAINER', 'OTHER')),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),

    -- Sibling names are unique, including among roots (PG16 treats NULL parents as equal here).
    CONSTRAINT ux_locations_sibling UNIQUE NULLS NOT DISTINCT (space_id, parent_location_id, normalized_name),

    -- Exists solely to make the composite same-space FK below legal.
    CONSTRAINT ux_locations_id_space UNIQUE (id, space_id),

    -- DB-level backstop for "a parent location must belong to the same space".
    -- MATCH SIMPLE skips enforcement when parent_location_id is NULL (root locations).
    CONSTRAINT fk_locations_parent_same_space
        FOREIGN KEY (parent_location_id, space_id) REFERENCES locations (id, space_id)
);

CREATE INDEX ix_locations_space ON locations (space_id);
CREATE INDEX ix_locations_parent ON locations (parent_location_id);
