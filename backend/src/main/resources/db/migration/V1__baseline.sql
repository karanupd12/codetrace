-- M1 baseline. PostgreSQL is authoritative; schema is owned by Flyway only.
CREATE TABLE schema_info (
    component  TEXT        PRIMARY KEY,
    version    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO schema_info (component, version) VALUES ('codetrace', '1');

-- Skeleton for durable job state; extended in M2 (repository FK, payload, etc.).
CREATE TABLE index_job (
    id              UUID        PRIMARY KEY,
    repository_id   UUID,
    operation       TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','DEAD_LETTERED')),
    attempts        INTEGER     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    error_category  TEXT,
    idempotency_key TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_index_job_idempotency UNIQUE (idempotency_key)
);
