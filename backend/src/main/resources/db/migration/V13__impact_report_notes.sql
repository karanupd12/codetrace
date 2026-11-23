-- Plain-language notes an analysis leaves for the reader, e.g. which heavily implemented interface methods it did not
-- follow to their callers. Empty for most reports.

ALTER TABLE impact_report ADD COLUMN notes JSONB NOT NULL DEFAULT '[]'::jsonb;
