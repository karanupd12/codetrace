# CODETRACE

**Know what a code change touches before you merge it.**

[![CI](https://github.com/karanupd12/codetrace/actions/workflows/ci.yml/badge.svg)](https://github.com/karanupd12/codetrace/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)
![Next.js 15](https://img.shields.io/badge/Next.js-15-black)
![Docker Compose](https://img.shields.io/badge/Docker-Compose-2496ED)

CODETRACE connects to a Git repository (a GitHub link or a local path), indexes its source code at an exact commit, and builds an evidence-backed picture of how the code fits together. From that picture it answers the question every reviewer asks: *if this changes, what else is affected?*

Everything it reports is deterministic and traceable to evidence. Where the tool cannot be sure, it says so instead of guessing. AI is only ever used to *explain* findings that were already computed; it never creates dependencies or changes a risk score.

---

## Table of contents
- [Features](#features)
- [How it works](#how-it-works)
- [Quick start](#quick-start)
- [Using CODETRACE](#using-codetrace)
- [API overview](#api-overview)
- [Configuration](#configuration)
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Security](#security)
- [Performance](#performance)
- [Development](#development)
- [Project structure](#project-structure)
- [Roadmap](#roadmap)

---

## Features

**Available now**
- **Connect any public repository.** Paste a GitHub, GitLab or Bitbucket HTTPS URL, or point to a Git repository on disk.
- **Pinned, reproducible indexing.** Pick a commit; the result is tied to that exact revision.
- **Java symbol extraction.** Types (classes, interfaces, enums, records, annotations, nested and inner types), methods and constructors with their overloads, fields, enum constants, record components and imports. Each symbol keeps its file, line range and parser version.
- **Honest parser diagnostics.** Malformed files are reported with line numbers and never stop the rest of the repository from being indexed.
- **Versioned dependency graph.** Packages, files, types, methods and fields, connected by `CONTAINS`, `IMPORTS`, `EXTENDS`, `IMPLEMENTS`, `REFERENCES` and `CALLS` edges. Every edge records *why* it exists (file, line, number of occurrences, extraction method, resolver version), a confidence, and a resolution state:
  - `RESOLVED`: the evidence points to exactly one target in the repository;
  - `AMBIGUOUS`: several candidates fit (for example overloads with the same number of arguments). All are kept, linked as alternatives, with lower confidence;
  - `UNRESOLVED`: the target is outside the repository or cannot be proven, with a reason such as `RECEIVER_TYPE_NOT_IN_REPOSITORY`.
- **Immutable, atomically published snapshots.** A graph is built for one commit, validated against integrity rules, and published in a single step. Readers never see a half-built graph, and published graphs cannot be modified.
- **Bounded graph queries.** Node search, neighbourhoods up to a chosen depth, and every shortest dependency path between two symbols, each with hard limits and a `truncated` flag.
- **Interactive graph explorer.** Search a symbol, see its neighbourhood laid out left to right, filter by edge type and resolution state, double-click to re-centre, and click any edge to see its evidence.
- **Asynchronous, fault-tolerant jobs.** Work is queued on Redis Streams with job state stored in PostgreSQL. Duplicate deliveries are harmless, failures are retried a bounded number of times, and exhausted jobs are dead-lettered.
- **Web dashboard.** Connect repositories, watch sync, index and graph progress, browse revisions, symbols and diagnostics, and explore the graph.
- **Runs anywhere with one command.** Docker Compose starts the whole stack.

**Coming next** (see the [roadmap](#roadmap))
- Git change detection and blast-radius analysis with a risk score.
- A downloadable Markdown impact report.
- Incremental indexing, semantic code search and AI-written explanations.

---

## How it works

```
 Git URL / local path
          │
          ▼
   ┌──────────────┐     ┌────────────────────────────────────────────┐
   │ 1. Register  │────▶│ validate source: allowlist, SSRF guard,    │
   └──────────────┘     │ local-root check, canonical form           │
          │             └────────────────────────────────────────────┘
          ▼
   ┌──────────────┐     bare, depth-limited clone (or open local repo
   │ 2. Sync      │────▶ read-only) → discover recent commits
   └──────────────┘
          │
          ▼
   ┌──────────────┐     read the chosen commit straight from Git
   │ 3. Index     │────▶ objects → filter and bound files → parse →
   └──────────────┘     symbols, imports, reference facts, diagnostics
          │             (one transaction: all-or-nothing)
          ▼
   ┌──────────────┐     resolve facts → nodes + edges with evidence →
   │ 4. Graph     │────▶ write → validate invariants → publish atomically
   └──────────────┘     (immutable snapshot)
          │
          ▼
   Dashboard / REST API: symbols, diagnostics, graph explorer, paths
```

1. **Register.** The source is validated and canonicalized. Registering the same source twice returns the same repository.
2. **Sync.** The worker fetches the repository and records the latest commits.
3. **Index.** For the commit you pick, the worker walks the Git tree and parses every Java file. **Nothing is checked out and no repository code is ever executed.** Besides declarations it records raw *reference facts*: which types a member mentions, which methods it calls, with how many arguments, and what is known about the receiver. Limits cap file count, file size, total bytes, symbol and reference counts, and time. Results are written in a single transaction.
4. **Build the graph.** A deterministic resolver turns the facts into edges. Type names are resolved through nested scopes, imports, the same package and wildcard imports. Calls are resolved through the receiver's type and its supertypes, nearest declaration first, matching on name and argument count. Anything it cannot prove is stored as ambiguous or unresolved with a reason, never guessed. The graph is written, checked against integrity rules in SQL, and then published with a single update.

---

## Quick start

### Prerequisites
- Docker Desktop (or Docker Engine) with Compose v2
- Git

### Run the whole stack
```bash
git clone https://github.com/karanupd12/codetrace.git
cd codetrace

cp .env.example .env              # Windows PowerShell: Copy-Item .env.example .env
./scripts/make-demo-repo.sh       # optional demo repo; PowerShell: .\scripts\make-demo-repo.ps1

docker compose up --build
```

| Service | URL |
|---|---|
| Dashboard | http://localhost:3000 |
| API | http://localhost:8080 |
| API health | http://localhost:8080/actuator/health |

Stop with `docker compose down` (add `-v` to also delete the database and cloned repositories).

---

## Using CODETRACE

### From the dashboard
1. Open http://localhost:3000.
2. Choose **GitHub / Git URL** and paste a public repository, for example `https://github.com/spring-guides/gs-rest-service`, then click **Connect**. (Or choose **Local path** and enter `/repos/demo-java` after running the demo script.)
3. Open the repository once it shows `SYNCED`, and click **Index** on a commit.
4. Browse **Symbols** (filter by kind or search by qualified name) and review **Parser diagnostics**.
5. Click **Build graph** on the indexed commit, then **Open graph** when the snapshot is published. Search for a type or method, adjust depth and edge filters, double-click a node to re-centre, and click an edge to see its evidence.

### From the API
```bash
# 1. Connect a repository (returns 202 and a sync job)
curl -s -X POST localhost:8080/api/v1/repositories \
  -H 'Content-Type: application/json' \
  -d '{"sourceType":"GIT_URL","location":"https://github.com/spring-guides/gs-rest-service"}'

# 2. Once syncStatus is SYNCED, index the latest commit
curl -s -X POST localhost:8080/api/v1/repositories/<repo-id>/index-jobs \
  -H 'Content-Type: application/json' -d '{}'

# 3. Check the job, then query symbols
curl -s localhost:8080/api/v1/index-jobs/<job-id>
curl -s "localhost:8080/api/v1/index-jobs/<job-id>/symbols?kind=METHOD&q=Greeting&limit=20"

# 4. Build the dependency graph for that commit (returns 202 with the snapshot and job)
curl -s -X POST localhost:8080/api/v1/repositories/<repo-id>/graph-snapshots \
  -H 'Content-Type: application/json' -d '{}'

# 5. Once the snapshot is PUBLISHED: find a node, its neighbourhood, and paths between nodes
curl -s "localhost:8080/api/v1/snapshots/<snapshot-id>/nodes?type=TYPE&q=greeting"
curl -s "localhost:8080/api/v1/snapshots/<snapshot-id>/graph?center=<node-id>&depth=2"
curl -s "localhost:8080/api/v1/snapshots/<snapshot-id>/paths?from=<node-id>&to=<node-id>&types=CONTAINS,CALLS,REFERENCES"
```

### Symbol naming
| Kind | Example |
|---|---|
| Type | `com.acme.shop.Order`, nested: `com.acme.shop.Order.Line` |
| Method (overloads stay distinct) | `com.acme.shop.Order#addLine(String)` and `…#addLine(String,int)` |
| Constructor | `com.acme.shop.Order#<init>()` |
| Field / enum constant | `com.acme.shop.Order#status`, `com.acme.shop.Order.Status#OPEN` |

Parameter types are recorded as written in the source; CODETRACE does not claim a resolution it has no evidence for.

---

## API overview

Base path `/api/v1`. All responses are JSON; errors follow [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807). Every response carries an `X-Correlation-Id` header. Server filesystem paths are never returned.

| Method | Path | Description |
|---|---|---|
| `GET` | `/system/info` | Role, version, database migration version, Redis status |
| `POST` | `/repositories` | Register a repository `{sourceType: "GIT_URL" \| "LOCAL", location}` (`202` new, `200` already registered) |
| `GET` | `/repositories`, `/repositories/{id}` | List or fetch repositories |
| `GET` | `/repositories/{id}/revisions` | Commits discovered by sync |
| `POST` | `/repositories/{id}/sync` | Fetch new commits |
| `POST` | `/repositories/{id}/index-jobs` | Index a commit `{commitHash?}` (defaults to HEAD); idempotent per commit |
| `GET` | `/repositories/{id}/index-jobs` | Recent jobs |
| `GET` | `/index-jobs/{jobId}` | Job state, attempts, error category, counters |
| `GET` | `/index-jobs/{jobId}/symbols` | Paged symbols (`kind`, `q`, `limit` ≤ 200, `offset`) |
| `GET` | `/index-jobs/{jobId}/diagnostics` | Paged parser diagnostics |
| `POST` | `/repositories/{id}/graph-snapshots` | Build the graph for an indexed commit `{commitHash?}`; idempotent per commit and builder version |
| `GET` | `/repositories/{id}/snapshots`, `/snapshots/{id}` | Snapshots with status and node/edge/resolution counts |
| `GET` | `/snapshots/{id}/nodes` | Paged node search (`type`, `q`, `path`, `limit` ≤ 500) |
| `GET` | `/snapshots/{id}/nodes/{nodeId}`, `/nodes/{nodeId}/edges` | Node detail with degree counts; its edges with full provenance |
| `GET` | `/snapshots/{id}/graph` | Bounded neighbourhood (`center`, `depth`, `direction`, `types`, `states`, `limit`) |
| `GET` | `/snapshots/{id}/paths` | All shortest paths between two nodes (`from`, `to`, `maxDepth`, `direction` OUT/IN, `types`), each marked certain or not |
| `GET` | `/actuator/health` | Liveness, readiness, database and Redis health |

Graph queries answer only for published snapshots (`409` otherwise). By default they follow every edge type except `CONTAINS`; add it to `types` to walk from a type into its members.

---

## Configuration

Configuration is by environment variables. Copy `.env.example` to `.env` for the Compose settings.

| Variable | Default | Purpose |
|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `codetrace` / `codetrace` / *(set it)* | Database credentials |
| `API_PORT`, `FRONTEND_PORT`, `POSTGRES_PORT`, `REDIS_PORT` | `8080`, `3000`, `5432`, `6379` | Published ports |
| `CODETRACE_CORS_ORIGINS` | `http://localhost:3000` | Browser origins allowed to call the API |
| `NEXT_PUBLIC_API_BASE_URL` | `http://localhost:8080` | API URL the dashboard calls (baked in at build time) |
| `CODETRACE_LOCAL_REPOS_DIR` | `./data/local-repos` | Host folder mounted read-only at `/repos` for local repositories |

Ingestion limits (set as `CODETRACE_INGESTION_<NAME>`, e.g. `CODETRACE_INGESTION_MAX_JAVA_FILES`):

| Setting | Default |
|---|---|
| `ALLOWED_HOSTS` | `github.com`, `gitlab.com`, `bitbucket.org` |
| `LOCAL_ROOTS` | *(empty = local repositories disabled)*; `/repos` in Compose |
| `CLONE_DEPTH` / `CLONE_TIMEOUT_SECONDS` | 50 commits / 120 s |
| `MAX_REPO_BYTES` | 200 MiB |
| `MAX_REVISIONS` | 50 |
| `MAX_JAVA_FILES` / `MAX_FILE_BYTES` / `MAX_TOTAL_BYTES` | 5000 / 512 KiB / 50 MiB |
| `MAX_SYMBOLS` / `MAX_REFERENCES` / `PARSE_DEADLINE_SECONDS` | 200 000 / 1 000 000 / 300 s |
| `MAX_ATTEMPTS` | 3 |

Graph limits (`CODETRACE_GRAPH_<NAME>`):

| Setting | Default |
|---|---|
| `MAX_NODES` / `MAX_EDGES` per snapshot | 500 000 / 2 000 000 |
| `MAX_QUERY_RESULTS` | 500 |
| `MAX_DEPTH` (neighbourhood) / `MAX_PATH_DEPTH` | 6 / 10 |
| `MAX_PATH_VISITED` (nodes explored by one path search) | 20 000 |

---

## Architecture

CODETRACE is a **modular monolith**: a single Spring Boot codebase that runs in two roles selected by profile, so the pieces can scale independently without becoming microservices.

```
 Browser ─▶ Next.js dashboard ─HTTP─▶ API (REST)  ─────SQL────▶ PostgreSQL ◀────SQL──── Worker
                                        │                                                 ▲
                                        └─ job id ─▶ Redis Streams (codetrace:jobs) ──────┘
                                                                    Worker ─▶ JGit ─▶ Git objects
```

- **API** (`SPRING_PROFILES_ACTIVE=api`): validation, orchestration, REST endpoints.
- **Worker** (`SPRING_PROFILES_ACTIVE=worker`): consumes jobs, clones/fetches, indexes. It exposes no REST endpoints.
- **PostgreSQL** is the single source of truth (schema managed by Flyway migrations). **Redis** only carries "look at job X" messages, so losing or duplicating one is harmless.

### Reliability model
- Jobs are *claimed* with one atomic conditional update, so only one worker runs a given job even if the message is delivered twice.
- Messages are acknowledged only after the outcome is safely stored. If a worker dies, its message is reclaimed and the job resumes.
- Transient failures retry up to 3 times with a delay; permanent failures (bad source, limit exceeded, missing revision) fail immediately; exhausted jobs go to a dead-letter stream.
- A sweeper republishes jobs whose original message was lost.

### Data model
`repository`, `revision`, `index_job` (with a unique idempotency key), `indexed_file`, `code_symbol`, `code_import`, `code_reference`, `parse_diagnostic`, and the graph tables `graph_snapshot`, `code_node`, `dependency_edge`. Constraints enforce valid states, full commit hashes and cascading deletes; line numbers are attributes, never identity.

Graph integrity is enforced by the database itself: composite foreign keys keep every edge inside its own snapshot, check constraints tie each resolution state to its required fields (an unresolved edge has no target, an ambiguous edge belongs to an alternative group), and triggers reject any change to a published snapshot. Node ids are derived from the node's identity, so rebuilding the same snapshot yields the same ids.

---

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Maven, Spring JDBC |
| Parsing & Git | JavaParser, JGit (reads Git objects directly, no `git` binary) |
| Data & queue | PostgreSQL 17 (pgvector image), Flyway, Redis 7.4 Streams |
| Frontend | Next.js 15 (App Router), React 19, TypeScript, React Flow, Dagre |
| Testing | JUnit 5, AssertJ, Mockito, Testcontainers, Awaitility |
| Delivery | Docker, Docker Compose, GitHub Actions |

---

## Security

Repository contents are treated as untrusted input.

- **Never executed.** Source is read from Git objects and parsed as text. Nothing is checked out, built, or run, so Git hooks, build scripts, tests and binaries in a repository have no effect.
- **Remote URLs** must be `https://` on an allowlisted host, with no embedded credentials, query string or odd ports. The host's DNS addresses must all be public (loopback, private, link-local and similar ranges are rejected), checked at registration and again before cloning.
- **Local repositories** are disabled unless you configure allowed root folders, and the resolved real path must be inside one of them.
- **Bounded work.** Clone depth and time, repository size, file count, per-file and total bytes, symbol and reference counts, indexing time, graph size, query depth and query result sizes are all capped.
- **No leaks.** API errors and stored job errors never contain server paths; logs carry ids and categories, not source code or credentials. Containers run as a non-root user.

Only public repositories are supported today. If you find a security issue, please contact the maintainer privately instead of opening a public issue.

---

## Performance

Figures below are measured, with the dataset, method and hardware stated so they can be reproduced. Areas not yet measured are listed as such.

**Baseline: [google/gson](https://github.com/google/gson) at commit `e5f02ab`.** Hardware: laptop with Intel Core i5-1240P, 8 GB RAM, Windows 11, Docker Desktop (16 vCPU / 3.7 GiB for containers), full Docker Compose stack. Single client on localhost. Job times come from the jobs' own start and finish timestamps. Query latencies are client-side over 20 sequential requests after one warm-up, including HTTP and JSON overhead.

| Step | Result |
|---|---|
| Sync (depth-50 clone over the network) | 14.6 s |
| Index | 268 Java files, 5,137 symbols in 4.7 s (≈ 57 files/s) |
| Graph build (resolve, write, validate, publish) | 5,428 nodes and 43,986 edges in 22.7 s |
| Edge resolution | 40.2 % resolved · 23.5 % ambiguous · 36.3 % unresolved |
| Call sites (15,998) | 37.5 % resolved · 11.3 % ambiguous · 51.2 % unresolved, mostly calls on JDK or test-library types that are not in the repository |
| Node search | p50 23 ms · p95 54 ms |
| Neighbourhood of `Gson`, depth 1 (200 edges) | p50 60 ms · p95 76 ms |
| Neighbourhood of `Gson`, depth 3 (500 edges) | p50 110 ms · p95 161 ms |
| All shortest paths `Gson → TypeAdapter` | p50 44 ms · p95 52 ms |

**Graph write throughput** (synthetic, from the integration test suite against PostgreSQL in Testcontainers): 30,000 nodes + 60,000 edges in 6.3 s (≈ 14,000 rows/s). Concurrent readers during that write never observed a partially published snapshot.

| Area | What will be reported | Status |
|---|---|---|
| Indexing throughput | files/s and symbols/s on small, medium and large repositories | One repository measured (above) |
| Query latency | p50 / p95 / p99 for symbol, graph and impact queries under load | Single-client baseline above; load tests not yet run |
| Impact analysis | analysis time versus graph size and traversal depth | Not yet measured |
| Incremental indexing | speed-up of incremental versus full re-index | Not yet measured |
| Resource use | peak heap and database size per 1 000 files | Not yet measured |
| Reliability | recovery time after a worker crash; duplicate-processing count | Not yet measured |

---

## Development

**Requirements:** JDK 21, Node.js 22, Docker (the integration tests start real PostgreSQL and Redis containers).

```bash
# Backend: unit + integration tests
cd backend
./mvnw clean verify          # Windows: .\mvnw.cmd clean verify

# Frontend: lint + production build
cd frontend
npm ci && npm run lint && npm run build
```

Tips
- Use `clean verify`; some IDEs write incomplete classes into `target/` that Maven would otherwise skip recompiling.
- If your default `java` is not 21, set `JAVA_HOME` before running Maven.
- `scripts/make-demo-repo.*` builds a small Git repository from `test-fixtures/demo-java` with nested types, overloads, imports, a deliberately malformed file and ignored paths.

GitHub Actions runs the backend tests, frontend lint/build and a Compose config check on every push.

---

## Project structure

```
codetrace/
├── backend/                     Spring Boot app (API + worker from one jar)
│   └── src/main/java/dev/codetrace/
│       ├── repository/          registration, revisions
│       ├── ingestion/           Git access, revision discovery, bounded indexer
│       ├── parser/              Java symbol extraction
│       ├── symbols/             persistence and queries for extracted symbols
│       ├── graph/               resolver, snapshot build/validate/publish, graph queries
│       ├── jobs/                queueing, claiming, retries, dead-lettering
│       ├── security/            source validation (allowlist, SSRF guard)
│       └── config/, system/     properties, error handling, health info
├── frontend/                    Next.js dashboard
├── test-fixtures/demo-java/     sample source tree used by tests and the demo script
├── scripts/                     demo repository builders
├── docker-compose.yml           full local stack
└── .github/workflows/ci.yml     CI pipeline
```

---

## Roadmap

- [x] Connect GitHub URLs and local repositories
- [x] Java symbol extraction with diagnostics
- [x] Asynchronous, idempotent job processing
- [x] Web dashboard for repositories, symbols and jobs
- [x] Versioned dependency graph with provenance and resolution state
- [x] Interactive dependency graph visualization
- [ ] Git change detection and blast-radius analysis with risk scoring
- [ ] Downloadable Markdown impact report
- [ ] Incremental indexing
- [ ] Semantic code search
- [ ] AI-written explanations that cite the evidence they explain
- [ ] Load tests and benchmarks on more repositories

---

## License

A license has not been chosen yet.
