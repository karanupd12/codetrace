-- HTTP endpoints declared with Spring MVC or JAX-RS annotations, extracted per INDEX job like the other facts.
-- A route or method that is not written as a literal is stored empty (route) or as '?' (method), with a reason.

CREATE TABLE code_endpoint (
    id                     BIGSERIAL PRIMARY KEY,
    job_id                 UUID    NOT NULL REFERENCES index_job (id) ON DELETE CASCADE,
    path                   TEXT    NOT NULL,
    handler_qualified_name TEXT    NOT NULL,
    http_method            TEXT    NOT NULL,
    route                  TEXT    NOT NULL DEFAULT '',
    framework              TEXT    NOT NULL CHECK (framework IN ('SPRING_MVC', 'JAX_RS')),
    reason                 TEXT    NOT NULL DEFAULT '',
    line                   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX ix_code_endpoint_job ON code_endpoint (job_id, path);
