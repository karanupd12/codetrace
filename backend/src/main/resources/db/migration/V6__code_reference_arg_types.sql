-- Type hints for the arguments of each call site, one per argument and separated by the ASCII unit separator
-- (U+001F; commas occur inside generic types). An empty hint means the text does not reveal that argument's type.
-- The graph builder uses them to tell apart overloads that take the same number of arguments.

ALTER TABLE code_reference ADD COLUMN arg_types TEXT NOT NULL DEFAULT '';
