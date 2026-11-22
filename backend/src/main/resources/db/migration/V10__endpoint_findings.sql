-- Impact reports list HTTP endpoints that a change adds, removes, modifies (its handler changed) or affects (its handler
-- depends on the change). They are stored as findings of type ENDPOINT; change_kind is null for an affected endpoint.

ALTER TABLE impact_finding DROP CONSTRAINT impact_finding_finding_type_check;
ALTER TABLE impact_finding ADD CONSTRAINT ck_impact_finding_type
    CHECK (finding_type IN ('CHANGED', 'AFFECTED', 'POSSIBLE', 'ENDPOINT'));
