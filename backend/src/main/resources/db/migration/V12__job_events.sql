-- Progress of background jobs, step by step, in plain words, so the dashboard can show what is happening while a job
-- runs. Written by the worker in its own transactions; a bounded number per attempt; removed with the job.

CREATE TABLE job_event (
    id       BIGSERIAL   PRIMARY KEY,
    job_id   UUID        NOT NULL REFERENCES index_job (id) ON DELETE CASCADE,
    attempt  INTEGER     NOT NULL,
    seq      INTEGER     NOT NULL,
    at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    step     TEXT        NOT NULL,
    message  TEXT        NOT NULL CHECK (length(message) <= 200),
    detail   JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uq_job_event_seq UNIQUE (job_id, seq)
);
