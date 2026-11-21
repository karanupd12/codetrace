-- Change impact reports. A report binds one base and one head snapshot to the algorithm version that produced it.
-- It is written by a background job in one transaction and is immutable once COMPLETED.

ALTER TABLE index_job DROP CONSTRAINT ck_index_job_operation;
ALTER TABLE index_job ADD CONSTRAINT ck_index_job_operation
    CHECK (operation IN ('SYNC', 'INDEX', 'GRAPH_BUILD', 'IMPACT'));

CREATE TABLE impact_report (
    id                 UUID        PRIMARY KEY,
    repository_id      UUID        NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    base_commit        TEXT        NOT NULL CHECK (base_commit ~ '^[0-9a-f]{40}$'),
    head_commit        TEXT        NOT NULL CHECK (head_commit ~ '^[0-9a-f]{40}$'),
    base_snapshot_id   UUID        NOT NULL REFERENCES graph_snapshot (id) ON DELETE CASCADE,
    head_snapshot_id   UUID        NOT NULL REFERENCES graph_snapshot (id) ON DELETE CASCADE,
    algorithm_version  TEXT        NOT NULL,
    depth              INTEGER     NOT NULL CHECK (depth BETWEEN 1 AND 20),
    job_id             UUID        NOT NULL UNIQUE REFERENCES index_job (id) ON DELETE CASCADE,
    status             TEXT        NOT NULL CHECK (status IN ('REQUESTED', 'RUNNING', 'COMPLETED', 'FAILED')),
    risk_score         INTEGER     CHECK (risk_score BETWEEN 0 AND 100),
    risk_level         TEXT        CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    risk_factors       JSONB,
    changed_count      INTEGER,
    affected_count     INTEGER,
    tests_affected_count INTEGER,
    possible_count     INTEGER,
    unmapped_hunks     INTEGER,
    non_java_files     INTEGER,
    truncated          BOOLEAN,
    failure_reason     TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ,
    CONSTRAINT ck_impact_distinct_commits CHECK (base_commit <> head_commit),
    CONSTRAINT ck_impact_completed_has_result CHECK (
        (status = 'COMPLETED') = (completed_at IS NOT NULL AND risk_score IS NOT NULL AND risk_level IS NOT NULL
                                  AND risk_factors IS NOT NULL AND affected_count IS NOT NULL)),
    CONSTRAINT uq_impact_report_identity UNIQUE (repository_id, base_snapshot_id, head_snapshot_id, algorithm_version, depth)
);
CREATE INDEX ix_impact_report_repo ON impact_report (repository_id, created_at DESC);

CREATE TABLE impact_finding (
    id                BIGSERIAL    PRIMARY KEY,
    report_id         UUID         NOT NULL REFERENCES impact_report (id) ON DELETE CASCADE,
    finding_type      TEXT         NOT NULL CHECK (finding_type IN ('CHANGED', 'AFFECTED', 'POSSIBLE')),
    ordinal           INTEGER      NOT NULL,
    name              TEXT         NOT NULL,
    node_type         TEXT         NOT NULL DEFAULT '',
    path              TEXT         NOT NULL DEFAULT '',
    line              INTEGER      NOT NULL DEFAULT 0,
    module            TEXT         NOT NULL DEFAULT '',
    is_test           BOOLEAN      NOT NULL DEFAULT FALSE,
    change_kind       TEXT         CHECK (change_kind IN ('ADDED', 'MODIFIED', 'REMOVED')),
    direct_dependents INTEGER,
    distance          INTEGER,
    certain           BOOLEAN,
    confidence        NUMERIC(4,3) CHECK (confidence BETWEEN 0 AND 1),
    changed_symbol    TEXT         NOT NULL DEFAULT '',
    node_id           UUID,
    chain             JSONB,
    CONSTRAINT ck_impact_finding_changed CHECK (finding_type <> 'CHANGED' OR change_kind IS NOT NULL),
    CONSTRAINT ck_impact_finding_affected CHECK (finding_type <> 'AFFECTED' OR (distance >= 1 AND certain IS NOT NULL AND confidence IS NOT NULL)),
    CONSTRAINT uq_impact_finding_order UNIQUE (report_id, finding_type, ordinal)
);
CREATE INDEX ix_impact_finding_report ON impact_finding (report_id, finding_type, ordinal);

-- A completed report is evidence: it never changes. Deleting it (or its repository/snapshot) still cascades.
CREATE FUNCTION guard_completed_impact_findings() RETURNS trigger AS $$
DECLARE
    rid UUID;
BEGIN
    rid := CASE WHEN TG_OP = 'DELETE' THEN OLD.report_id ELSE NEW.report_id END;
    IF EXISTS (SELECT 1 FROM impact_report WHERE id = rid AND status = 'COMPLETED') THEN
        RAISE EXCEPTION 'impact report % is completed and immutable', rid USING ERRCODE = 'check_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_impact_finding_immutable BEFORE INSERT OR UPDATE OR DELETE ON impact_finding
    FOR EACH ROW EXECUTE FUNCTION guard_completed_impact_findings();

CREATE FUNCTION guard_completed_impact_report() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'COMPLETED' THEN
        RAISE EXCEPTION 'impact report % is completed and immutable', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_impact_report_immutable BEFORE UPDATE ON impact_report
    FOR EACH ROW EXECUTE FUNCTION guard_completed_impact_report();
