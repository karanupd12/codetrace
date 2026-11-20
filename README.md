# CODETRACE
Evidence-driven codebase change-impact intelligence.

Index Git repositories, build versioned dependency graphs, and analyze proposed changes to identify affected symbols, modules, contracts, and tests. Deterministic analysis is authoritative; AI explains supplied evidence.

## Stack
Java 21 · Spring Boot 3.x · Maven · PostgreSQL/pgvector · Redis Streams · JavaParser/Symbol Solver · JGit · Tree-sitter (later) · Spring AI · Gemini/Ollama · Next.js · TypeScript · React Flow · Docker Compose · JUnit 5 · Testcontainers · JMH · k6.

## Quick start
```bash
cp .env.example .env
docker compose up --build
```
UI: http://localhost:3000 · API health: http://localhost:8080/actuator/health. See `docs/DEVELOPMENT.md` for tests and builds.

**Status:** milestone M1 (runnable foundation) implemented, awaiting acceptance. Repository connection (GitHub URL or local path) arrives in M2.

## Read first
1. `CLAUDE.md`
2. `docs/ARCHITECTURE.md`
3. `docs/DOMAIN_AND_API.md`
4. `docs/IMPLEMENTATION_PLAN.md`
5. `docs/DEVELOPMENT.md`

## Rules
Modular monolith first. Java is the first supported language. PostgreSQL is source of truth; Redis is transport. Never execute repository code. LLMs cannot invent graph edges or alter deterministic findings. Every report identifies its revision and snapshot. Complete milestones sequentially and wait for explicit approval before advancing.
