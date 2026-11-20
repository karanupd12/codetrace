# CODETRACE

**Know what a code change touches before you merge it.**

[![CI](https://github.com/karanupd12/codetrace/actions/workflows/ci.yml/badge.svg)](https://github.com/karanupd12/codetrace/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)
![Next.js 15](https://img.shields.io/badge/Next.js-15-black)
![Docker Compose](https://img.shields.io/badge/Docker-Compose-2496ED)

CODETRACE connects to a Git repository (a GitHub link or a local folder), reads the code at an exact commit, and builds a map of how the pieces depend on each other. You can then explore that map to answer the question every reviewer asks: *if this changes, what else is affected?*

It never guesses silently. When it cannot be sure which code something points to, it says so.

---

## Contents
- [Features](#features)
- [How it works](#how-it-works)
- [Quick start](#quick-start)
- [Using CODETRACE](#using-codetrace)
- [Tech stack](#tech-stack)
- [Performance](#performance)
- [Contributing](#contributing)
- [Roadmap](#roadmap)

---

## Features

- **Connect any public repository.** Paste a GitHub, GitLab or Bitbucket link, or point to a Git repository on disk.
- **Pinned to a commit.** Every result belongs to one exact revision, so it is reproducible.
- **Java code understanding.** Finds classes, interfaces, enums, records, methods, constructors and fields, and who uses whom.
- **Dependency graph.** Shows what calls, extends, implements or references what, and for every link tells you *where in the code* it came from.
- **Honest about uncertainty.** Each link is marked as *certain*, *ambiguous* (several possible targets) or *unresolved* (outside the repository).
- **Interactive graph explorer.** Search a class or method, see what surrounds it, filter by link type, and click a link to see its evidence.
- **Safe by design.** Your code is read, never run.
- **One command to start.** Docker Compose runs the whole thing.

---

## How it works

```
 Repository  →  Sync  →  Index  →  Build graph  →  Explore
 (URL / path)   fetch    read &     connect the     search, filter,
                commits  parse Java dots           follow dependencies
```

1. **Sync** downloads the repository history.
2. **Index** reads the Java files of a chosen commit and records what is declared and what refers to what.
3. **Build graph** connects those facts into a graph and saves it as a finished, read-only snapshot.
4. **Explore** lets you browse the snapshot in the dashboard or through the API.

Heavy work runs in the background, so the dashboard stays responsive. Nothing from the repository is ever compiled or executed.

---

## Quick start

**You need:** Docker (with Compose) and Git.

```bash
git clone https://github.com/karanupd12/codetrace.git
cd codetrace

cp .env.example .env              # Windows PowerShell: Copy-Item .env.example .env
docker compose up --build
```

Then open **http://localhost:3000**.

| Service | URL |
|---|---|
| Dashboard | http://localhost:3000 |
| API | http://localhost:8080 |

Stop it with `docker compose down` (add `-v` to also delete stored data).

> Open the dashboard at exactly `http://localhost:3000`. The API only accepts requests from that address by default.

**Optional demo repository:** run `./scripts/make-demo-repo.sh` (Windows: `.\scripts\make-demo-repo.ps1`) and connect `/repos/demo-java` as a local path.

---

## Using CODETRACE

1. Open the dashboard and paste a repository link, for example `https://github.com/google/gson`, then click **Connect**.
2. When it shows `SYNCED`, open the repository and click **Index** on a commit.
3. Click **Build graph**, then **Open graph**.
4. Search for a class or method, change the depth, toggle link types, and click a link to see its evidence.

**Prefer the API?**

```bash
curl -X POST localhost:8080/api/v1/repositories \
  -H 'Content-Type: application/json' \
  -d '{"sourceType":"GIT_URL","location":"https://github.com/google/gson"}'
```

The response contains the repository id. Index it with `POST /api/v1/repositories/{id}/index-jobs`, build the graph with `POST /api/v1/repositories/{id}/graph-snapshots`, and query it under `/api/v1/snapshots/{snapshotId}/` (`nodes`, `graph`, `paths`).

---

## Tech stack

| Area | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Maven |
| Code parsing and Git | JavaParser, JGit |
| Data and queue | PostgreSQL 17, Redis Streams, Flyway migrations |
| Frontend | Next.js 15, React 19, TypeScript, React Flow |
| Testing | JUnit 5, Testcontainers, Mockito |
| Delivery | Docker Compose, GitHub Actions |

The backend is a single codebase that runs in two roles: an **API** that serves the dashboard and a **worker** that does the background jobs.

---

## Performance

Measured on [google/gson](https://github.com/google/gson) (268 Java files) on a laptop (Intel Core i5-1240P, 8 GB RAM, Docker Desktop), one user, full Docker Compose stack.

| Step | Result |
|---|---|
| Download the repository | 14.6 s |
| Index 268 files (5,137 symbols) | 4.7 s |
| Build the graph (5,428 nodes, 43,986 links) | 22.7 s |
| Open a class's neighbourhood | under 0.2 s |
| Find all shortest paths between two classes | about 50 ms |

About 40 % of the links are certain, 24 % ambiguous and 36 % unresolved. Most unresolved links point at Java or test-library code that is not part of the repository.

These are single-user baselines; load tests are on the roadmap.

---

## Contributing

Contributions are welcome.

1. **Fork** the repository and create a branch from `main`.
2. **Set up:** JDK 21, Node.js 22 and Docker.
3. **Run the checks** before opening a pull request:
   ```bash
   cd backend && ./mvnw clean verify        # Windows: .\mvnw.cmd clean verify
   cd frontend && npm ci && npm run lint && npm run build
   ```
4. **Keep changes focused** and add tests for new behaviour.
5. **Write clear commit messages** in the conventional style, for example `feat(graph): add ...` or `fix(parser): handle ...`.
6. **Open a pull request** describing what changed and why. CI must pass.

Found a bug or have an idea? Open an issue. For a security problem, please contact the maintainer privately instead of opening a public issue.

---

## Roadmap

- [x] Connect GitHub links and local repositories
- [x] Java code extraction
- [x] Dependency graph with evidence for every link
- [x] Interactive graph explorer
- [ ] Detect what changed between two commits
- [ ] Show the blast radius of a change with a risk score
- [ ] Downloadable Markdown impact report
- [ ] Faster updates by re-indexing only what changed
- [ ] Semantic code search
- [ ] AI explanations that cite the evidence they explain
- [ ] Load tests and benchmarks on more repositories

---

## License

A license has not been chosen yet.
