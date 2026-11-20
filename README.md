<div align="center">

# CODETRACE

### Know what a code change touches before you merge it.

Connect a Git repository, and CODETRACE turns its source into an **evidence-backed dependency graph** you can search, filter and follow, with a clear record of *why* every connection exists and *how sure* the tool is about it.

[![CI](https://github.com/karanupd12/codetrace/actions/workflows/ci.yml/badge.svg)](https://github.com/karanupd12/codetrace/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)
![Next.js 15](https://img.shields.io/badge/Next.js-15-black)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-336791)
![Docker Compose](https://img.shields.io/badge/Docker-Compose-2496ED)

[Why CODETRACE](#why-codetrace) · [Features](#features) · [Quick start](#quick-start) · [How it works](#how-it-works) · [Performance](#performance) · [Contributing](#contributing)

</div>

---

## Why CODETRACE

Reviewing a change usually means guessing what else it might break. Text search misses indirect callers, overloaded methods and interface implementations, and a tool that *pretends* to know is worse than none.

CODETRACE takes the opposite approach:

- **Evidence, not guesses.** Every dependency points to the file and line that justify it.
- **Uncertainty is visible.** If several targets are plausible, or the target lives outside the repository, the link says so instead of picking one silently.
- **Reproducible.** Results are tied to one exact commit and never change after they are published.
- **Safe on untrusted code.** Repositories are read and parsed as text. Nothing is ever built or executed.

---

## Features

| | |
|---|---|
| **Connect anything public** | A GitHub, GitLab or Bitbucket HTTPS link, or a Git repository on disk. |
| **Pinned to a commit** | Pick any recent revision; the analysis belongs to that exact commit. |
| **Java code model** | Classes, interfaces, enums, records, annotations, nested types, methods and constructors (overloads kept distinct), fields and imports. |
| **Dependency graph** | `CALLS`, `REFERENCES`, `EXTENDS`, `IMPLEMENTS`, `IMPORTS` and `CONTAINS` links between packages, files, types, methods and fields. |
| **Resolution states** | Each link is `RESOLVED` (one provable target), `AMBIGUOUS` (several candidates, all kept and linked as alternatives) or `UNRESOLVED` (outside the repository, with a reason code). |
| **Provenance on every link** | Source file and line, number of occurrences, confidence score, and the extractor and resolver versions that produced it. |
| **Immutable snapshots** | A graph is validated, then published in a single atomic step. Readers never see a half-built graph, and published graphs cannot be modified. |
| **Interactive explorer** | Search a symbol, expand its neighbourhood, filter by link type and state, re-centre with a double-click, and click a link to read its evidence. |
| **Bounded queries** | Neighbourhoods, and every shortest path between two symbols, with hard limits and a `truncated` flag so you always know when you are seeing a partial view. |
| **Resilient background jobs** | Redis Streams with PostgreSQL as the source of truth: safe retries, dead-lettering, and no duplicate work when a message is delivered twice. |

---

## Quick start

**Requirements:** Docker with Compose v2, and Git.

```bash
git clone https://github.com/karanupd12/codetrace.git
cd codetrace

cp .env.example .env              # Windows PowerShell: Copy-Item .env.example .env
docker compose up --build
```

| Service | URL |
|---|---|
| Dashboard | http://localhost:3000 |
| API | http://localhost:8080 |
| Health check | http://localhost:8080/actuator/health |

> Open the dashboard at exactly `http://localhost:3000`. The API's CORS allow-list contains that origin by default (change it with `CODETRACE_CORS_ORIGINS`).

Stop with `docker compose down`. Add `-v` to also delete the database and cloned repositories.

### Monitoring the jobs (optional)

Start two extra tools alongside the stack to watch background work as it happens:

```bash
docker compose --profile tools up -d
```

| Tool | URL | What you see |
|---|---|---|
| **RedisInsight** | http://localhost:5540 | The `codetrace:jobs` stream and dead-letter stream, the `indexers` consumer group, pending messages and lag. The connection to Redis is pre-configured. |
| **Dozzle** | http://localhost:8888 | Live, searchable logs of every container: each job's attempts and outcome in the worker, and each request in the API. |
| **Worker health** | http://localhost:8081/actuator/health | Whether the worker can reach PostgreSQL and Redis (also used by the Compose health check). |

These tools listen on `127.0.0.1` only. Dozzle reads the Docker socket, so keep it local. Stop them with `docker compose --profile tools stop redisinsight dozzle`.

**Try a local repository:** run `./scripts/make-demo-repo.sh` (Windows: `.\scripts\make-demo-repo.ps1`), then connect `/repos/demo-java` as a local path.

---

## Using CODETRACE

**In the dashboard**

1. Paste a repository link, for example `https://github.com/google/gson`, and click **Connect**.
2. When the status shows `SYNCED`, open the repository and click **Index** on a commit.
3. Click **Build graph**, then **Open graph**.
4. Search for a class or method, adjust depth and filters, and click a link to see its evidence.

**From the API**

```bash
# 1. Connect a repository (202 Accepted, a sync job starts)
curl -X POST localhost:8080/api/v1/repositories \
  -H 'Content-Type: application/json' \
  -d '{"sourceType":"GIT_URL","location":"https://github.com/google/gson"}'

# 2. Index the latest commit, then build its dependency graph
curl -X POST localhost:8080/api/v1/repositories/<repo-id>/index-jobs      -H 'Content-Type: application/json' -d '{}'
curl -X POST localhost:8080/api/v1/repositories/<repo-id>/graph-snapshots -H 'Content-Type: application/json' -d '{}'

# 3. Query the published snapshot
curl "localhost:8080/api/v1/snapshots/<snapshot-id>/nodes?type=TYPE&q=Gson"
curl "localhost:8080/api/v1/snapshots/<snapshot-id>/graph?center=<node-id>&depth=2"
curl "localhost:8080/api/v1/snapshots/<snapshot-id>/paths?from=<a>&to=<b>&types=CONTAINS,CALLS,REFERENCES"
```

| Endpoint | Purpose |
|---|---|
| `POST /repositories` | Register a source (`GIT_URL` or `LOCAL`); idempotent |
| `POST /repositories/{id}/index-jobs` | Parse one commit into symbols and reference facts |
| `POST /repositories/{id}/graph-snapshots` | Build and publish the dependency graph for an indexed commit |
| `GET /snapshots/{id}/nodes` | Paged node search |
| `GET /snapshots/{id}/graph` | Bounded neighbourhood around a node |
| `GET /snapshots/{id}/paths` | All shortest paths between two nodes, flagged *certain* or not |

Errors follow [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807), and every response carries an `X-Correlation-Id` header. Graph queries answer only for published snapshots (`409` otherwise).

---

## How it works

```
 Git URL / local path
        │
        ▼
 ┌─────────────┐   validate source (allow-list, SSRF guard, local-root check)
 │ 1. Register │   then canonicalise, so the same repo is never added twice
 └──────┬──────┘
        ▼
 ┌─────────────┐   bare, depth-limited clone (or read-only open of a local repo)
 │ 2. Sync     │   and discover recent commits
 └──────┬──────┘
        ▼
 ┌─────────────┐   read files straight from Git objects, with no checkout; parse
 │ 3. Index    │   Java; store declarations, imports and raw reference facts
 └──────┬──────┘   in one all-or-nothing transaction
        ▼
 ┌─────────────┐   resolve facts into nodes and edges with evidence, write them,
 │ 4. Graph    │   validate invariants in SQL, then publish atomically
 └──────┬──────┘
        ▼
   Dashboard and REST API: search, neighbourhoods, shortest paths
```

### The graph model

| Node types | `PACKAGE` · `FILE` · `TYPE` · `METHOD` · `FIELD` |
|---|---|
| **Edge types** | `CONTAINS` · `IMPORTS` · `EXTENDS` · `IMPLEMENTS` · `REFERENCES` · `CALLS` |
| **Edge states** | `RESOLVED` · `AMBIGUOUS` · `UNRESOLVED` |

Type names are resolved through nested scopes, single imports, the same package and wildcard imports. Method calls are resolved through the receiver's type and its supertypes, nearest declaration first, matching on name and argument count.

When the evidence is not enough, CODETRACE records that fact instead of guessing:

| Situation | Result |
|---|---|
| One provable target | `RESOLVED`, confidence 1.0 (or 0.9 when picked by argument count) |
| Several overloads fit equally | One `AMBIGUOUS` edge per candidate, sharing an alternative group, confidence 0.9 ÷ candidates |
| Target is a JDK or third-party type | `UNRESOLVED` with a reason such as `RECEIVER_TYPE_NOT_IN_REPOSITORY` |
| Receiver type cannot be determined | `UNRESOLVED` with `RECEIVER_TYPE_UNKNOWN` |

### Architecture

CODETRACE is a **modular monolith**: one Spring Boot codebase that runs in two roles chosen by profile, so each can scale on its own without becoming microservices.

```
 Browser ─▶ Next.js dashboard ─HTTP─▶ API ─────SQL────▶ PostgreSQL ◀────SQL──── Worker
                                       │                                          ▲
                                       └─ job id ─▶ Redis Streams ────────────────┘
                                                              Worker ─▶ JGit ─▶ Git objects
```

- **API** serves REST and validates requests. **Worker** consumes jobs; it exposes no REST endpoints.
- **PostgreSQL is the source of truth** (Flyway migrations). Redis only carries "look at job X" messages, so losing or duplicating one is harmless.
- **Idempotent jobs:** a worker *claims* a job with one atomic conditional `UPDATE`, so only one worker runs it even if the message arrives twice. Messages are acknowledged only after the outcome is stored; stalled ones are reclaimed, transient failures are retried up to three times, and exhausted jobs land in a dead-letter stream.
- **Database-enforced integrity:** composite foreign keys keep edges inside their own snapshot, check constraints tie each resolution state to its required fields, and triggers reject any change to a published snapshot. Node ids are deterministic, so rebuilding a snapshot yields identical ids.

---

## Performance

Measured on [google/gson](https://github.com/google/gson) at commit `e5f02ab`: 268 Java files, 5,137 symbols.

**Hardware:** laptop, Intel Core i5-1240P, 8 GB RAM, Windows 11, Docker Desktop. Full Compose stack, a single client on localhost. Job times are the jobs' own start and finish timestamps. Query latencies are client-side, over 20 sequential requests after one warm-up, and include HTTP and JSON overhead.

| Step | Result |
|---|---|
| Sync (depth-50 clone over the network) | 14.6 s |
| Index | 4.7 s (≈ 57 files/s, ≈ 1,090 symbols/s) |
| Graph build (resolve, write, validate, publish) | 22.7 s for 5,428 nodes and 43,986 edges |

| Query | p50 | p95 |
|---|---|---|
| Node search | 23 ms | 54 ms |
| Neighbourhood of `Gson`, depth 1 (200 edges) | 60 ms | 76 ms |
| Neighbourhood of `Gson`, depth 3 (500 edges) | 110 ms | 161 ms |
| All shortest paths `Gson → TypeAdapter` | 44 ms | 52 ms |

*p50 is the median; p95 means 95 % of requests finished at least that fast. p99 and concurrent-load figures are not published yet because they have not been measured.*

**Graph write throughput:** 30,000 nodes + 60,000 edges in 6.3 s (≈ 14,200 rows/s) on PostgreSQL in Testcontainers, using batched multi-row inserts. Concurrent readers during that write never observed a partially published snapshot.

**Resolution quality on gson:** of 43,986 edges, 40.2 % are resolved, 23.5 % ambiguous and 36.3 % unresolved. Of 15,998 call sites, 37.5 % resolve to one target, 11.3 % are ambiguous (several overloads with the same argument count), and 51.2 % are unresolved, mostly calls on JDK or test-library types that are not part of the repository.

---

## Security

Repository contents are treated as **untrusted input**.

- **Never executed.** Source is read from Git objects and parsed as text. No checkout, build, test or script runs, so hooks and build files inside a repository have no effect.
- **Remote URLs** must be `https://` on an allow-listed host (GitHub, GitLab and Bitbucket by default), with no credentials, query string or unusual port. Every DNS address must be public, checked at registration and again before cloning (SSRF guard).
- **Local repositories** are disabled unless you configure root folders, and the resolved real path must sit inside one of them.
- **Bounded work.** Clone depth and time, repository size, file count, per-file and total bytes, symbol and reference counts, parse time, graph size, query depth and result sizes are all capped.
- **No leaks.** Errors and logs carry ids and categories, never server paths, source code or credentials. Containers run as a non-root user.

Only public repositories are supported today. If you find a vulnerability, please contact the maintainer privately instead of opening a public issue.

---

## Configuration

Settings are environment variables; copy `.env.example` to `.env` for the Compose values.

| Variable | Default | Purpose |
|---|---|---|
| `CODETRACE_CORS_ORIGINS` | `http://localhost:3000` | Browser origins allowed to call the API |
| `CODETRACE_LOCAL_REPOS_DIR` | `./data/local-repos` | Host folder mounted read-only at `/repos` |
| `CODETRACE_INGESTION_ALLOWED_HOSTS` | `github.com`, `gitlab.com`, `bitbucket.org` | Hosts a remote URL may use |
| `CODETRACE_INGESTION_MAX_JAVA_FILES` | `5000` | Files indexed per commit |
| `CODETRACE_GRAPH_MAX_NODES` / `_MAX_EDGES` | `500000` / `2000000` | Size cap per snapshot |
| `CODETRACE_GRAPH_MAX_QUERY_RESULTS` | `500` | Upper bound on any query result |

All limits live under `CODETRACE_INGESTION_*` and `CODETRACE_GRAPH_*`.

---

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Maven, Spring JDBC (explicit SQL, no ORM) |
| Parsing and Git | JavaParser, JGit (reads Git objects directly, no `git` binary) |
| Data and queue | PostgreSQL 17 with `pg_trgm`, Flyway, Redis 7.4 Streams |
| Frontend | Next.js 15 (App Router), React 19, TypeScript, React Flow, Dagre |
| Testing | JUnit 5, AssertJ, Mockito, Testcontainers, Awaitility |
| Delivery | Docker, Docker Compose, GitHub Actions |
| Monitoring (optional) | RedisInsight, Dozzle, Spring Boot Actuator |

---

## Contributing

Contributions are welcome, whether a bug fix, a test, documentation or a new idea.

1. **Fork** the repository and branch from `main`.
2. **Set up:** JDK 21, Node.js 22 and Docker (the integration tests start real PostgreSQL and Redis containers).
3. **Run the checks** before opening a pull request:
   ```bash
   cd backend  && ./mvnw clean verify                    # Windows: .\mvnw.cmd clean verify
   cd frontend && npm ci && npm run lint && npm run build
   ```
4. **Keep changes focused** and add tests for new behaviour.
5. **Use conventional commits**, for example `feat(graph): ...`, `fix(parser): ...`, `docs: ...`.
6. **Open a pull request** that explains what changed and why. CI must pass.

Tips: use `clean verify` (some IDEs leave stale classes in `target/`), and set `JAVA_HOME` to JDK 21 if your default `java` is older. `scripts/make-demo-repo.*` builds a small repository from `test-fixtures/demo-java` with nested types, overloads, a deliberately malformed file and ignored paths.

Found a bug or have a question? Open an issue.

```
codetrace/
├── backend/        Spring Boot app (API + worker from one jar)
│   └── src/main/java/dev/codetrace/
│       ├── repository/  registration and revisions      ├── graph/     resolver, snapshots, queries
│       ├── ingestion/   Git access and bounded indexer   ├── jobs/      queueing, claiming, retries
│       ├── parser/      Java extraction                  └── security/  source validation
├── frontend/       Next.js dashboard
├── test-fixtures/  sample source trees for tests and demos
└── docker-compose.yml
```

---

## License

A license has not been chosen yet.
