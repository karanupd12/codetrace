<div align="center">

# CODETRACE

### Know what a code change could break before you merge it.

Connect a Git repository, pick the version before and after a change, and CODETRACE tells you **what changed, what depends on it and which tests to run**, with the file and line behind every claim and an honest note wherever it is not sure.

[![CI](https://github.com/karanupd12/codetrace/actions/workflows/ci.yml/badge.svg)](https://github.com/karanupd12/codetrace/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)
![Next.js 15](https://img.shields.io/badge/Next.js-15-black)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-336791)
![Docker Compose](https://img.shields.io/badge/Docker-Compose-2496ED)

[Why CODETRACE](#why-codetrace) · [Features](#features) · [Quick start](#quick-start) · [Using it](#using-codetrace) · [How it works](#how-it-works) · [Performance](#performance) · [Contributing](#contributing)

</div>

---

## Why CODETRACE

Reviewing a change usually means guessing what else it might break. Text search misses indirect callers, overloaded methods and interface implementations, and a tool that *pretends* to know is worse than none.

CODETRACE takes the opposite approach:

- **Evidence, not guesses.** Every dependency points to the file and line that justify it, and every affected symbol shows the chain of calls that links it to the change.
- **Uncertainty is visible.** When several targets are plausible, or a call may run a different implementation, the result is marked *possibly* instead of being silently counted or dropped. What it deliberately does not follow is written down in the report.
- **Reproducible.** Results are tied to exact commits and never change after they are published.
- **Safe on untrusted code.** Repositories are read and parsed as text. Nothing is ever built or executed.

---

## Features

**For people reviewing changes**

| | |
|---|---|
| **Analyze a change** | Pick a *before* and *after* version by commit message. CODETRACE finds the classes, methods and fields that were added, changed or removed, then lists the production code that depends on them and the **tests to run**, nearest first, each with a "Why?" that shows the chain of calls. |
| **A risk score that explains itself** | A 0–100 score and level (low, medium, high, critical) built from seven independent checks: how much changed, how much depends on it, breaking API changes, module spread, uncertainty, missing tests and analysis limits. The summary says why in plain sentences. |
| **API endpoints** | Spring MVC and JAX-RS endpoints are read from the code. A change is reported as adding, removing, changing or reaching an endpoint (`GET /orders/{id}`), and a removed endpoint counts as a breaking change. |
| **Tests found two ways** | Through the code they call, and by name (`OrderTest`, `OrderTests`, `OrderIT`, `TestOrder` → `Order`), so a test that works through a mock is still listed. |
| **Interfaces and implementations** | Changing an implementation reaches the callers of the interface or abstract method it implements, marked *possibly* because a call may run another implementation. For a widely implemented method, the report names it instead of flooding the results. |
| **Markdown report** | A downloadable report that names the exact versions, dependency maps and algorithm, so anyone can reproduce it. |

**For exploring a codebase**

| | |
|---|---|
| **Dependency map** | An interactive map of classes, methods and fields and how they call, use, extend, implement, override and test each other. Click anything to see what uses it and what it uses, with the file and line of each link. |
| **"How is A connected to B?"** | Pick any two parts of the code and see the shortest chains of dependencies between them, step by step. |
| **Compare versions** | What code and which connections appeared or disappeared between two versions. |
| **Code browser** | Every class, method and field, the API endpoints, and any file that could not be read, for any scanned version. |

**Built for a smooth experience**

| | |
|---|---|
| **One-click preparation** | "Prepare" scans a version and maps its dependencies without further clicks. Analyzing a version that is not ready prepares it first. |
| **Live progress** | Every background task shows each step as it happens, in plain words ("Reading the code", "Read 268 files (259 unchanged, reused)", "Building the dependency map", "Analysis ready: Medium risk"), with durations, retries and a "Try again" on failure. |
| **Fast repeat scans** | A new version reuses everything that did not change since an earlier scan, matched by file content, so renamed files are reused too. |
| **Light and dark themes** | A clean, GitHub-inspired interface with solid colours and readable contrast. Every page keeps its state in the URL, so links and refreshes keep your place. |

**Under the hood**

| | |
|---|---|
| **Versioned, immutable results** | Each dependency map belongs to one commit and one resolver version, is validated in SQL and published in a single atomic step. Readers never see a half-built map, and published results cannot be modified. |
| **Provenance on every link** | Resolution state (`RESOLVED`, `AMBIGUOUS` with all candidates kept, or `UNRESOLVED` with a reason), confidence, source file and line, and the versions of the code that produced it. |
| **Resilient background jobs** | Redis Streams with PostgreSQL as the source of truth: safe retries, dead-lettering, no duplicate work when a message is delivered twice, and a daily clean-up of superseded results. |

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

**Try a local repository:** run `./scripts/make-demo-repo.sh` (Windows: `.\scripts\make-demo-repo.ps1`), then connect `/repos/demo-java`.

### Monitoring the jobs (optional)

```bash
docker compose --profile tools up -d
```

| Tool | URL | What you see |
|---|---|---|
| **RedisInsight** | http://localhost:5540 | The job streams, the consumer group, pending messages and lag (pre-configured). |
| **Dozzle** | http://localhost:8888 | Live, searchable logs of every container. |
| **Worker health** | http://localhost:8081/actuator/health | Whether the worker can reach PostgreSQL and Redis. |

These tools listen on `127.0.0.1` only. Dozzle reads the Docker socket, so keep it local.

---

## Using CODETRACE

**In the dashboard**

1. Paste a repository link, for example `https://github.com/google/gson`, and click **Connect**.
2. On **Overview**, click **Prepare** on a version. Watch each step as it scans the code and maps its dependencies.
3. Open **Analyze a change**, pick the *before* and *after* versions, and click **Analyze**. Versions that are not prepared yet are prepared first.
4. Read the result: the risk and why, what changed, what could break, tests to run and any API endpoints touched. Open "Why?" on any row for its evidence, or download the Markdown report.
5. Use **Explore** to browse the dependency map, ask "How is A connected to B?" or compare two versions. **Code** lists classes, endpoints and unreadable files; **Activity** shows every task and its steps.

**From the API**

```bash
# 1. Connect a repository (202 Accepted, a sync job starts)
curl -X POST localhost:8080/api/v1/repositories \
  -H 'Content-Type: application/json' \
  -d '{"sourceType":"GIT_URL","location":"https://github.com/google/gson"}'

# 2. Scan a commit, then build its dependency map (follow progress with /index-jobs/{id}/events)
curl -X POST localhost:8080/api/v1/repositories/<repo-id>/index-jobs      -H 'Content-Type: application/json' -d '{"commitHash":"<sha>"}'
curl -X POST localhost:8080/api/v1/repositories/<repo-id>/graph-snapshots -H 'Content-Type: application/json' -d '{"commitHash":"<sha>"}'

# 3. Explore a published map
curl "localhost:8080/api/v1/snapshots/<snapshot-id>/nodes?type=TYPE&q=Gson"
curl "localhost:8080/api/v1/snapshots/<snapshot-id>/paths?from=<a>&to=<b>"
curl "localhost:8080/api/v1/snapshots/<base-snapshot>/compare/<head-snapshot>"

# 4. Analyse a change between two prepared versions, then read or download the report
curl -X POST localhost:8080/api/v1/repositories/<repo-id>/impact-reports \
  -H 'Content-Type: application/json' -d '{"baseCommit":"<sha>","headCommit":"<sha>","depth":4}'
curl "localhost:8080/api/v1/impact-reports/<report-id>/findings?type=AFFECTED&test=true&limit=50"
curl -OJ "localhost:8080/api/v1/impact-reports/<report-id>/report.md"
```

| Endpoint | Purpose |
|---|---|
| `POST /repositories` | Register a source (`GIT_URL` or `LOCAL`); idempotent |
| `POST /repositories/{id}/index-jobs` | Scan one commit into symbols, reference facts and HTTP endpoints (unchanged files are reused) |
| `POST /repositories/{id}/graph-snapshots` | Build and publish the dependency map of a scanned commit |
| `GET /index-jobs/{id}/events`, `GET /repositories/{id}/activity` | A job's progress steps in plain words; the newest steps of all of a repository's jobs |
| `GET /index-jobs/{id}/symbols`, `/endpoints`, `/diagnostics` | What a scan found, paged |
| `GET /snapshots/{id}/nodes`, `/nodes/{node}`, `/nodes/{node}/edges` | Search, a node's degree counts, its connections with provenance |
| `GET /snapshots/{id}/graph`, `/paths` | Bounded neighbourhood; all shortest paths between two nodes |
| `GET /snapshots/{base}/compare/{head}` (+ `/nodes`, `/edges`) | What appeared, disappeared or changed between two versions |
| `POST /repositories/{id}/impact-reports` | Analyse the change between two prepared versions; idempotent |
| `GET /impact-reports/{id}` | Risk score, level, the reason behind each check, and notes |
| `GET /impact-reports/{id}/findings` | Paged changed, affected, possible and endpoint findings with evidence (`type`, `module`, `test` filters) |
| `GET /impact-reports/{id}/report.md` | The Markdown report |

Errors follow [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807), and every response carries an `X-Correlation-Id` header. Map queries answer only for published snapshots, and findings only for completed reports (`409` otherwise).

---

## How it works

```
 Git URL / local path
        │
        ▼
 ┌─────────────┐   validate the source (host allow-list, public addresses only, local-root check)
 │ 1. Register │   and canonicalise it, so the same repository is never added twice
 └──────┬──────┘
        ▼
 ┌─────────────┐   bare, depth-limited clone with redirects refused and the size limit enforced
 │ 2. Sync     │   during the download (or a read-only open of a local repository)
 └──────┬──────┘
        ▼
 ┌─────────────┐   read files straight from Git objects, with no checkout; copy the facts of
 │ 3. Scan     │   unchanged files from an earlier scan; parse the rest (declarations, references,
 └──────┬──────┘   argument types, HTTP endpoints) and store everything in one transaction
        ▼
 ┌─────────────┐   resolve facts into nodes and links with evidence, write them,
 │ 4. Map      │   validate invariants in SQL, then publish atomically
 └──────┬──────┘
        ▼
 ┌─────────────┐   diff before and after, map changed lines to symbols, walk the map backwards,
 │ 5. Analyze  │   compare endpoints, score the risk, store an immutable report
 └──────┬──────┘
        ▼
   Dashboard and REST API, with every step reported live as it happens
```

### How a change is analysed

1. **Diff.** The two commits' trees are compared directly in Git, with rename detection. A small lexer narrows each changed region to lines that hold code, so edits to comments, Javadoc or blank lines change nothing.
2. **Changed symbols.** Each region marks the *innermost* declaration it touches: an edit inside a method marks the method, not its class. A changed signature is a removal plus an addition, which is exactly what breaks callers.
3. **What could break.** A breadth-first search follows calls, uses, `extends` and `implements` *backwards* in the **before** version's map, the code that relied on the old behaviour. It also steps from a changed implementation to the callers of the abstract or interface method it implements, when that method has at most five implementations. It is bounded by depth and size and safe on cycles.
4. **Tests to run.** Tests reached that way, plus test classes named after a changed or affected class.
5. **Evidence.** Every affected symbol keeps the shortest chain of links to the change, with file and line per step. It is *proven* only if every step is certain. Calls whose receiver type is unknown are listed separately for a manual check and never counted.
6. **Risk, endpoints and report.** Seven independent checks add up to a 0–100 score; endpoints are compared by method and route; anything deliberately not followed becomes a plain note. The report binds both maps and the algorithm version, becomes read-only once complete, and renders the same Markdown every time.

### The dependency map

| Node types | `PACKAGE` · `FILE` · `TYPE` · `METHOD` · `FIELD` |
|---|---|
| **Link types** | `CONTAINS` · `IMPORTS` · `EXTENDS` · `IMPLEMENTS` · `OVERRIDES` · `REFERENCES` · `CALLS` · `TESTS` |
| **Link states** | `RESOLVED` · `AMBIGUOUS` · `UNRESOLVED` |

Type names are resolved through nested scopes, single and wildcard imports, the same package and static imports. A method call is resolved through the receiver's type and its supertypes, nearest declaration first. Between overloads with the same number of arguments, the argument types written in the code (literals, `new`, casts, declared variables) pick the applicable one, following Java's rules for widening, boxing and varargs.

When the evidence is not enough, CODETRACE records that fact instead of guessing:

| Situation | Result |
|---|---|
| One provable target | `RESOLVED`, confidence 1.0 (0.9 when chosen by argument count or types) |
| Several overloads still fit | One `AMBIGUOUS` link per candidate, sharing an alternative group, confidence 0.9 ÷ candidates |
| Target is a JDK or third-party type | `UNRESOLVED` with a reason such as `RECEIVER_TYPE_NOT_IN_REPOSITORY` |
| Receiver type cannot be determined | `UNRESOLVED` with `RECEIVER_TYPE_UNKNOWN` |
| Test class named after a class | `TESTS` link, confidence 0.8 (a naming match) |

### Architecture

CODETRACE is a **modular monolith**: one Spring Boot codebase that runs in two roles chosen by profile, so each can scale on its own without becoming microservices.

```
 Browser ─▶ Next.js dashboard ─HTTP─▶ API ─────SQL────▶ PostgreSQL ◀────SQL──── Worker
                                       │                                          ▲
                                       └─ job id ─▶ Redis Streams ────────────────┘
                                                              Worker ─▶ JGit ─▶ Git objects
```

- **API** serves REST and validates requests. **Worker** runs jobs and records each step as it goes; it exposes no REST endpoints.
- **PostgreSQL is the source of truth** (Flyway migrations). Redis only carries "look at job X" messages, so losing or duplicating one is harmless.
- **Idempotent jobs:** a worker *claims* a job with one atomic conditional `UPDATE`, so only one worker runs it even if the message arrives twice. Messages are acknowledged only after the outcome is stored; stalled ones are reclaimed, transient failures are retried up to three times, and exhausted jobs land in a dead-letter stream.
- **Database-enforced integrity:** composite foreign keys keep links inside their own map, check constraints tie each resolution state to its required fields, and triggers reject any change to a published map or a completed report.

---

## Performance

All figures were measured on [google/gson](https://github.com/google/gson) (268 Java files, 5,137 symbols) with the full Compose stack and a single client on localhost.

**Hardware:** laptop, Intel Core i5-1240P, 8 GB RAM, Windows 11, Docker Desktop. Job times are the jobs' own start and finish timestamps; query latencies are client-side and include HTTP and JSON.

| Step | Result |
|---|---|
| Sync (depth-50 clone over the network) | 14.6 s |
| Scan, full (`codetrace.ingestion.incremental=false`) | 3.6–4.7 s per commit (two rounds of three commits) |
| Scan, incremental (next commit, 259 of 268 files reused) | 1.2–1.7 s per commit (three rounds), about 2.5× faster |
| Dependency map build (resolve, write, validate, publish) | 3.9–5.2 s over eight from-scratch rebuilds of one commit, measured before override and test links were added; single builds with the current resolver took 5–16 s. 5,428 nodes and about 38,600 links |

| Query (20 sequential requests after warm-up, centre `JsonArray`, measured before override and test links were added) | p95 |
|---|---|
| Node search | 13–18 ms |
| Neighbourhood, depth 1 | 8–9 ms |
| Neighbourhood, depth 3 (223 nodes, 499 links) | 73–81 ms |
| All shortest paths | 7–9 ms |

*p95 means 95 % of requests finished at least that fast; ranges cover three runs. Concurrent-load figures are not published because they have not been measured.*

**Resolution quality on gson:** 48.5 % of links are resolved, 10.2 % ambiguous and 41.3 % unresolved (mostly calls on JDK or test-library types outside the repository). Choosing overloads by argument types reduced ambiguous links from 10,352 to 3,940.

**Change impact on gson:** commit `7fc4d18` ("Add JsonPrimitive.TRUE, FALSE, and of(boolean)") against its parent, depth 4.

| Result | Value |
|---|---|
| Analysis job (diff, map, traverse, score, store) | 3.7 s |
| Changed symbols | 14 |
| Affected | 6 production symbols and 32 tests (15 marked *possibly*), plus 18 calls to check by hand |
| Risk | 35 / 100, medium |
| Notes | Callers of `TypeAdapter#read`, implemented by 46 classes, were not followed |

**Accuracy on labelled changes:** five hand-labelled scenarios run through the full pipeline (a body change, a signature change, deleting unused code, a caller whose receiver type is unknown, and a change reaching callers through an interface). Each scored precision 1.00 and recall 1.00. The fixture is small and uses constructs the resolver supports, so treat this as a correctness check rather than a general accuracy figure.

---

## Security

Repository contents are treated as **untrusted input**.

- **Never executed.** Source is read from Git objects and parsed as text. No checkout, build, test or script runs, so hooks and build files inside a repository have no effect.
- **Remote URLs** must be `https://` on an allow-listed host (GitHub, GitLab and Bitbucket by default), with no credentials, query string or unusual port. Every DNS address must be public, checked at registration and again right before cloning. Clones refuse HTTP redirects.
- **Local repositories** are disabled unless you configure root folders, and the resolved real path must sit inside one of them.
- **Bounded work.** Clone depth and time, repository size (enforced during the download), file count, per-file and total bytes, per-file parse time, symbol and reference counts, map size, query depth and result sizes are all capped.
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
| `CODETRACE_INGESTION_MAX_JAVA_FILES` | `5000` | Files scanned per commit |
| `CODETRACE_INGESTION_INCREMENTAL` | `true` | Reuse the facts of unchanged files from an earlier scan |
| `CODETRACE_INGESTION_FILE_PARSE_SECONDS` | `10` | A file that takes longer to parse is skipped and reported |
| `CODETRACE_GRAPH_MAX_NODES` / `_MAX_EDGES` | `500000` / `2000000` | Size cap per dependency map |
| `CODETRACE_IMPACT_MAX_DISPATCH_IMPLEMENTATIONS` | `5` | Follow an interface method to its callers only up to this many implementations |
| `CODETRACE_RETENTION_ENABLED` | `true` | Daily clean-up of superseded results and old failures |

All limits live under `CODETRACE_INGESTION_*`, `CODETRACE_GRAPH_*`, `CODETRACE_IMPACT_*` and `CODETRACE_RETENTION_*`.

---

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Maven, Spring JDBC (explicit SQL, no ORM) |
| Parsing and Git | JavaParser, JGit (reads Git objects directly, no `git` binary) |
| Data and queue | PostgreSQL 17 with `pg_trgm`, Flyway, Redis 7.4 Streams |
| Frontend | Next.js 15 (App Router), React 19, TypeScript, React Flow, Dagre; no UI library |
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
5. **Use conventional commits**, for example `feat(graph): ...`, `fix(ingestion): ...`, `docs: ...`.
6. **Open a pull request** that explains what changed and why. CI must pass.

Tips: use `clean verify` (some IDEs leave stale classes in `target/`), and set `JAVA_HOME` to JDK 21 if your default `java` is older. `scripts/make-demo-repo.*` builds a small repository from `test-fixtures/demo-java` with nested types, overloads, a deliberately malformed file and ignored paths.

Found a bug or have a question? Open an issue.

```
codetrace/
├── backend/          Spring Boot app (API + worker from one jar), feature-first packages
│   └── src/main/java/dev/codetrace/
│       ├── source/     registered repositories      ├── graph/    resolver, maps, queries, comparison
│       ├── ingestion/  Git access, parsing, scans   ├── impact/   change detection, analysis, risk
│       ├── symbols/    what a scan found            ├── report/   Markdown report
│       ├── jobs/       queue, retries, progress     └── system/   status and daily clean-up
├── frontend/         Next.js dashboard (app/, components/, lib/)
├── test-fixtures/    sample source trees for tests and demos
└── docker-compose.yml
```

---

## License

A license has not been chosen yet.
