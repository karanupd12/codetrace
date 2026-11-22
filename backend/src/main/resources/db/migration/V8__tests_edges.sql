-- TESTS edges link a test class to the production class it is named after (FooTest, FooTests, FooIT, FooITCase,
-- TestFoo -> Foo). They are a naming heuristic, recorded with evidence kind NAMING_CONVENTION and lower confidence.

ALTER TABLE dependency_edge DROP CONSTRAINT dependency_edge_edge_type_check;
ALTER TABLE dependency_edge ADD CONSTRAINT ck_edge_type
    CHECK (edge_type IN ('CONTAINS', 'CALLS', 'REFERENCES', 'EXTENDS', 'IMPLEMENTS', 'IMPORTS', 'TESTS'));
