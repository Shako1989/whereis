CREATE TABLE item_files (
    id                 uuid PRIMARY KEY,
    item_id            uuid         NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    bucket             varchar(100) NOT NULL,
    object_key         varchar(512) NOT NULL,
    original_file_name varchar(255) NOT NULL,
    content_type       varchar(100) NOT NULL,
    file_size          bigint       NOT NULL,
    is_primary         boolean      NOT NULL DEFAULT false,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ux_item_files_key UNIQUE (object_key)
);

-- At most one primary image per item.
CREATE UNIQUE INDEX ux_item_files_primary ON item_files (item_id) WHERE is_primary;
CREATE INDEX ix_item_files_item ON item_files (item_id);

-- Outbox for MinIO deletions (PG and MinIO share no transaction).
CREATE TABLE storage_deletion_queue (
    id              uuid PRIMARY KEY,
    bucket          varchar(100) NOT NULL,
    object_key      varchar(512) NOT NULL,
    attempts        int          NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL DEFAULT now(),
    last_error      text,
    created_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX ix_sdq_next_attempt ON storage_deletion_queue (next_attempt_at);
