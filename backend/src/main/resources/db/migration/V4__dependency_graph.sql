-- Versioned dependency graph. A snapshot is built in staging, validated, then published atomically; a published
-- snapshot is immutable (enforced by triggers). Every node and edge belongs to one repository revision and snapshot.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE index_job DROP CONSTRAINT ck_index_job_operation;
ALTER TABLE index_job ADD CONSTRAINT ck_index_job_operation CHECK (operation IN ('SYNC', 'INDEX', 'GRAPH_BUILD'));

CREATE TABLE graph_snapshot (
    id                    UUID        PRIMARY KEY,
    repository_id         UUID        NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    commit_hash           TEXT        NOT NULL CHECK (commit_hash ~ '^[0-9a-f]{40}$'),
    parser_version        TEXT        NOT NULL,
    builder_version       TEXT        NOT NULL,
    source_job_id         UUID        REFERENCES index_job (id) ON DELETE SET NULL,
    status                TEXT        NOT NULL
                                      CHECK (status IN ('REQUESTED', 'BUILDING', 'VALIDATING', 'PUBLISHED', 'FAILED')),
    node_count            INTEGER,
    edge_count            INTEGER,
    resolved_edge_count   INTEGER,
    ambiguous_edge_count  INTEGER,
    unresolved_edge_count INTEGER,
    failure_reason        TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at          TIMESTAMPTZ,
    -- Snapshot identity: repository, full commit, parser version, builder version. Same identity = same snapshot.
    CONSTRAINT uq_graph_snapshot_identity UNIQUE (repository_id, commit_hash, parser_version, builder_version),
    CONSTRAINT ck_graph_snapshot_published CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);
CREATE INDEX ix_graph_snapshot_repo ON graph_snapshot (repository_id, created_at DESC);

CREATE TABLE code_node (
    id                UUID    PRIMARY KEY,
    snapshot_id       UUID    NOT NULL REFERENCES graph_snapshot (id) ON DELETE CASCADE,
    repository_id     UUID    NOT NULL,
    commit_hash       TEXT    NOT NULL,
    node_type         TEXT    NOT NULL CHECK (node_type IN ('FILE', 'PACKAGE', 'TYPE', 'METHOD', 'FIELD')),
    qualified_name    TEXT    NOT NULL,
    signature         TEXT    NOT NULL DEFAULT '',
    simple_name       TEXT    NOT NULL,
    subtype           TEXT    NOT NULL DEFAULT '',
    path              TEXT    NOT NULL DEFAULT '',
    start_line        INTEGER NOT NULL DEFAULT 0,
    end_line          INTEGER NOT NULL DEFAULT 0,
    modifiers         TEXT    NOT NULL DEFAULT '',
    extraction_method TEXT    NOT NULL,
    parser_version    TEXT    NOT NULL,
    CONSTRAINT uq_code_node_snapshot_id UNIQUE (snapshot_id, id),
    CONSTRAINT uq_code_node_identity UNIQUE (snapshot_id, node_type, path, qualified_name, signature)
);
CREATE INDEX ix_code_node_type ON code_node (snapshot_id, node_type);
CREATE INDEX ix_code_node_path ON code_node (snapshot_id, path);
CREATE INDEX ix_code_node_name_trgm ON code_node USING gin (lower(qualified_name) gin_trgm_ops);

CREATE TABLE dependency_edge (
    id                BIGSERIAL PRIMARY KEY,
    snapshot_id       UUID    NOT NULL REFERENCES graph_snapshot (id) ON DELETE CASCADE,
    repository_id     UUID    NOT NULL,
    commit_hash       TEXT    NOT NULL,
    src_node_id       UUID    NOT NULL,
    dst_node_id       UUID,                         -- null exactly when the edge is UNRESOLVED
    edge_type         TEXT    NOT NULL CHECK (edge_type IN ('CONTAINS', 'CALLS', 'REFERENCES', 'EXTENDS', 'IMPLEMENTS', 'IMPORTS')),
    dst_name          TEXT    NOT NULL DEFAULT '',  -- what an UNRESOLVED edge was looking for
    resolution_state  TEXT    NOT NULL CHECK (resolution_state IN ('RESOLVED', 'AMBIGUOUS', 'UNRESOLVED')),
    confidence        NUMERIC(4, 3) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    evidence_kind     TEXT    NOT NULL,
    reason            TEXT    NOT NULL DEFAULT '',
    alternative_group TEXT    NOT NULL DEFAULT '',  -- shared by the candidate edges of one AMBIGUOUS reference
    evidence_path     TEXT    NOT NULL DEFAULT '',
    evidence_line     INTEGER NOT NULL DEFAULT 0,
    occurrences       INTEGER NOT NULL DEFAULT 1 CHECK (occurrences >= 1),
    extraction_method TEXT    NOT NULL,
    resolver_version  TEXT    NOT NULL,
    -- Edges can only connect nodes of their own snapshot.
    CONSTRAINT fk_edge_src FOREIGN KEY (snapshot_id, src_node_id) REFERENCES code_node (snapshot_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_edge_dst FOREIGN KEY (snapshot_id, dst_node_id) REFERENCES code_node (snapshot_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_edge_unresolved_has_no_target CHECK ((resolution_state = 'UNRESOLVED') = (dst_node_id IS NULL)),
    CONSTRAINT ck_edge_ambiguous_has_group CHECK ((resolution_state = 'AMBIGUOUS') = (alternative_group <> '')),
    CONSTRAINT ck_edge_resolved_confidence CHECK (resolution_state = 'UNRESOLVED' OR confidence > 0)
);
CREATE UNIQUE INDEX uq_dependency_edge ON dependency_edge (
    snapshot_id, src_node_id, edge_type,
    COALESCE(dst_node_id, '00000000-0000-0000-0000-000000000000'::uuid),
    (CASE WHEN dst_node_id IS NULL THEN dst_name ELSE '' END),
    resolution_state);
CREATE INDEX ix_edge_src ON dependency_edge (snapshot_id, src_node_id, edge_type);
CREATE INDEX ix_edge_dst ON dependency_edge (snapshot_id, dst_node_id, edge_type) WHERE dst_node_id IS NOT NULL;
CREATE INDEX ix_edge_state ON dependency_edge (snapshot_id, resolution_state);

-- ---- immutability: a PUBLISHED snapshot can never gain, change or lose graph rows -------------------------------------
-- Deletes that cascade from removing the snapshot or repository still work: by then the snapshot row is already gone.
CREATE FUNCTION guard_published_graph_rows() RETURNS trigger AS $$
DECLARE
    sid UUID;
BEGIN
    sid := CASE WHEN TG_OP = 'DELETE' THEN OLD.snapshot_id ELSE NEW.snapshot_id END;
    IF EXISTS (SELECT 1 FROM graph_snapshot WHERE id = sid AND status = 'PUBLISHED') THEN
        RAISE EXCEPTION 'graph snapshot % is published and immutable', sid USING ERRCODE = 'check_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_code_node_immutable BEFORE INSERT OR UPDATE OR DELETE ON code_node
    FOR EACH ROW EXECUTE FUNCTION guard_published_graph_rows();
CREATE TRIGGER trg_dependency_edge_immutable BEFORE INSERT OR UPDATE OR DELETE ON dependency_edge
    FOR EACH ROW EXECUTE FUNCTION guard_published_graph_rows();

CREATE FUNCTION guard_published_snapshot() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'graph snapshot % is published and immutable', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_graph_snapshot_immutable BEFORE UPDATE ON graph_snapshot
    FOR EACH ROW EXECUTE FUNCTION guard_published_snapshot();
