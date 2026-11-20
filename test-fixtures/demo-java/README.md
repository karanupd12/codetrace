# demo-java fixture

Source tree used by tests and by `scripts/make-demo-repo.*` to build a small Git repository.
It deliberately contains: nested/inner types, overloads, varargs, a record, an enum, imports (plain, static, wildcard),
a malformed file (`Broken.java`), and ignored paths (`target/`, `node_modules/`). This file is not Java and is skipped.
