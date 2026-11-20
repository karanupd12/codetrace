-- Raw, unresolved reference facts extracted per INDEX job (type usages, extends/implements, call sites).
-- The graph builder resolves these into edges; nothing here claims a resolved relationship.

ALTER TABLE index_job ADD COLUMN extraction_version INTEGER;

CREATE TABLE code_reference (
    id                   BIGSERIAL PRIMARY KEY,
    job_id               UUID    NOT NULL REFERENCES index_job (id) ON DELETE CASCADE,
    path                 TEXT    NOT NULL,
    owner_qualified_name TEXT    NOT NULL,
    kind                 TEXT    NOT NULL CHECK (kind IN ('EXTENDS', 'IMPLEMENTS', 'REFERENCES', 'CALLS')),
    name                 TEXT    NOT NULL,
    arg_count            INTEGER NOT NULL DEFAULT -1,
    receiver_kind        TEXT    NOT NULL DEFAULT 'NONE'
                                 CHECK (receiver_kind IN ('NONE', 'THIS', 'SUPER', 'TYPE', 'UNKNOWN')),
    receiver_type        TEXT    NOT NULL DEFAULT '',
    line                 INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX ix_code_reference_job ON code_reference (job_id, path);
