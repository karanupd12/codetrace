# CODETRACE
Evidence-driven codebase change-impact intelligence.

Index Git repositories, build versioned dependency graphs, and analyze proposed changes to identify affected symbols, modules, contracts, and tests. Deterministic analysis is authoritative; AI explains supplied evidence.

**For everything about what exists today, how it works, trade-offs, data model, tests and metrics, read [`DOCUMENTATION.md`](DOCUMENTATION.md).**

## Stack
Java 21 · Spring Boot 3.x · Maven · PostgreSQL/pgvector · Redis Streams · JavaParser/Symbol Solver · JGit · Tree-sitter (later) · Spring AI · Gemini/Ollama · Next.js · TypeScript · React Flow · Docker Compose · JUnit 5 · Testcontainers · JMH · k6.

## Quick start
```bash
cp .env.example .env
./scripts/make-demo-repo.sh      # PowerShell: .\scripts\make-demo-repo.ps1
docker compose up --build
```
UI: http://localhost:3000 — connect `https://github.com/spring-guides/gs-rest-service` or the local path `/repos/demo-java`. API health: http://localhost:8080/actuator/health. See `docs/DEVELOPMENT.md` for tests and builds.

**Status:** M1 accepted; M2 (repository ingestion and Java parsing) implemented and awaiting acceptance. Graph, impact analysis, reports, retrieval and AI are planned (M3–M6).

## Read first
1. `DOCUMENTATION.md` (current state)
2. `CLAUDE.md`
3. `docs/ARCHITECTURE.md`
4. `docs/DOMAIN_AND_API.md`
5. `docs/IMPLEMENTATION_PLAN.md`
6. `docs/DEVELOPMENT.md`

## Rules
Modular monolith first. Java is the first supported language. PostgreSQL is source of truth; Redis is transport. Never execute repository code. LLMs cannot invent graph edges or alter deterministic findings. Every report identifies its revision and snapshot. Complete milestones sequentially and wait for explicit approval before advancing.
