-- M2: repository registration, revisions, indexing job state, extracted symbols and diagnostics.
-- Symbols here are staging data keyed by job + revision; M3 turns them into versioned graph snapshots.

CREATE TABLE repository (
    id             UUID        PRIMARY KEY,
    source_type    TEXT        NOT NULL CHECK (source_type IN ('LOCAL', 'GIT_URL')),
    location       TEXT        NOT NULL,   -- server-side only; never returned for LOCAL
    display_name   TEXT        NOT NULL,
    sync_status    TEXT        NOT NULL DEFAULT 'PENDING' CHECK (sync_status IN ('PENDING', 'SYNCED', 'FAILED')),
    default_branch TEXT,
    head_commit    TEXT        CHECK (head_commit ~ '^[0-9a-f]{40}$'),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_repository_source UNIQUE (source_type, location)
);

CREATE TABLE revision (
    id            UUID        PRIMARY KEY,
    repository_id UUID        NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    commit_hash   TEXT        NOT NULL CHECK (commit_hash ~ '^[0-9a-f]{40}$'),
    summary       TEXT        NOT NULL DEFAULT '',
    author_time   TIMESTAMPTZ,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_revision UNIQUE (repository_id, commit_hash)
);

ALTER TABLE index_job
    ALTER COLUMN repository_id SET NOT NULL,
    ADD CONSTRAINT fk_index_job_repository FOREIGN KEY (repository_id) REFERENCES repository (id) ON DELETE CASCADE,
    ADD CONSTRAINT ck_index_job_operation CHECK (operation IN ('SYNC', 'INDEX')),
    ADD COLUMN commit_hash TEXT CHECK (commit_hash ~ '^[0-9a-f]{40}$'),
    ADD COLUMN error_message TEXT,
    ADD COLUMN started_at TIMESTAMPTZ,
    ADD COLUMN finished_at TIMESTAMPTZ,
    ADD COLUMN files_parsed INTEGER,
    ADD COLUMN files_skipped INTEGER,
    ADD COLUMN symbols_extracted INTEGER,
    ADD COLUMN parser_version TEXT;

CREATE INDEX ix_index_job_repo ON index_job (repository_id, created_at DESC);
CREATE INDEX ix_index_job_status ON index_job (status, updated_at);

CREATE TABLE indexed_file (
    job_id       UUID    NOT NULL REFERENCES index_job (id) ON DELETE CASCADE,
    path         TEXT    NOT NULL,
    package_name TEXT    NOT NULL DEFAULT '',
    status       TEXT    NOT NULL CHECK (status IN ('PARSED', 'PARTIAL', 'FAILED', 'SKIPPED')),
    skip_reason  TEXT    NOT NULL DEFAULT '',
    size_bytes   BIGINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (job_id, path)
);

CREATE TABLE code_symbol (
    id                    UUID    PRIMARY KEY,
    job_id                UUID    NOT NULL REFERENCES index_job (id) ON DELETE CASCADE,
    repository_id         UUID    NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    commit_hash           TEXT    NOT NULL,
    path                  TEXT    NOT NULL,
    language              TEXT    NOT NULL DEFAULT 'java',
    kind                  TEXT    NOT NULL CHECK (kind IN ('TYPE', 'METHOD', 'FIELD')),
    subtype               TEXT    NOT NULL,
    simple_name           TEXT    NOT NULL,
    qualified_name        TEXT    NOT NULL,
    signature             TEXT    NOT NULL DEFAULT '',
    parent_qualified_name TEXT    NOT NULL DEFAULT '',
    modifiers             TEXT    NOT NULL DEFAULT '',
    start_line            INTEGER NOT NULL,
    end_line              INTEGER NOT NULL,
    extraction_method     TEXT    NOT NULL,
    parser_version        TEXT    NOT NULL,
    CONSTRAINT uq_code_symbol_identity UNIQUE (job_id, path, kind, qualified_name, signature)
);
CREATE INDEX ix_code_symbol_job_kind ON code_symbol (job_id, kind);
CREATE INDEX ix_code_symbol_job_name ON code_symbol (job_id, lower(qualified_name));

CREATE TABLE code_import (
    id            BIGSERIAL PRIMARY KEY,
    job_id        UUID    NOT NULL REFERENCES index_job (id) ON DELETE CASCADE,
    path          TEXT    NOT NULL,
    imported_name TEXT    NOT NULL,
    is_static     BOOLEAN NOT NULL,
    is_wildcard   BOOLEAN NOT NULL,
    line          INTEGER NOT NULL
);
CREATE INDEX ix_code_import_job ON code_import (job_id, path);

CREATE TABLE parse_diagnostic (
    id       BIGSERIAL PRIMARY KEY,
    job_id   UUID    NOT NULL REFERENCES index_job (id) ON DELETE CASCADE,
    path     TEXT    NOT NULL,
    severity TEXT    NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'ERROR')),
    code     TEXT    NOT NULL,
    message  TEXT    NOT NULL,
    line     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX ix_parse_diagnostic_job ON parse_diagnostic (job_id, path);
