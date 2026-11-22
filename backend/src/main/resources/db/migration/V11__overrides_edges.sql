-- OVERRIDES edges link a method to the supertype method it overrides or implements (same name and parameters), so a
-- change to an implementation can reach the callers of the interface or superclass method it stands in for.

ALTER TABLE dependency_edge DROP CONSTRAINT ck_edge_type;
ALTER TABLE dependency_edge ADD CONSTRAINT ck_edge_type
    CHECK (edge_type IN ('CONTAINS', 'CALLS', 'REFERENCES', 'EXTENDS', 'IMPLEMENTS', 'IMPORTS', 'TESTS', 'OVERRIDES'));
