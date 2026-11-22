-- Incremental indexing: each indexed file records the Git blob it was read from, so a later INDEX job of the same
-- repository can copy the facts of files whose content is unchanged instead of parsing them again.
-- An empty blob_id (rows written before this migration) never matches, so older indexes are simply not reused.

ALTER TABLE indexed_file ADD COLUMN blob_id TEXT NOT NULL DEFAULT '';

-- The earlier INDEX job whose facts were copied, and how many files were copied rather than parsed.
ALTER TABLE index_job ADD COLUMN base_job_id UUID REFERENCES index_job (id) ON DELETE SET NULL;
ALTER TABLE index_job ADD COLUMN files_reused INTEGER;
