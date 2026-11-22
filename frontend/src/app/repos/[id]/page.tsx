"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import {
  getRepository,
  isActive,
  listImpactReports,
  listJobs,
  listRevisions,
  listSnapshots,
  requestGraphBuild,
  reportInProgress,
  requestIndex,
  resync,
  snapshotInProgress,
  type Job,
  type Snapshot,
} from "@/lib/api";
import { usePoll } from "@/lib/usePoll";
import styles from "../../page.module.css";
import GraphExplorer from "./GraphExplorer";
import ImpactPanel from "./ImpactPanel";
import SymbolBrowser from "./SymbolBrowser";

const short = (h: string | null) => (h ? h.slice(0, 7) : "—");

function jobSummary(j: Job) {
  if (j.status === "SUCCEEDED" && j.operation === "INDEX") {
    const reused = j.filesReused ? ` (${j.filesReused} unchanged, reused)` : "";
    return `${j.filesParsed} files parsed${reused} · ${j.filesSkipped} skipped · ${j.symbolsExtracted} symbols`;
  }
  if (j.errorCategory) return `${j.errorCategory}: ${j.errorMessage ?? ""}`;
  return `attempt ${j.attempts}`;
}

export default function RepoPage() {
  const { id } = useParams<{ id: string }>();
  const [actionError, setActionError] = useState<string | null>(null);
  const [selectedJob, setSelectedJob] = useState<string | null>(null);
  const [openSnapshot, setOpenSnapshot] = useState<string | null>(null);

  const { data, error, reload } = usePoll(
    async (signal) => {
      const [repo, jobs, snapshots, reports] = await Promise.all([
        getRepository(id, signal),
        listJobs(id, signal),
        listSnapshots(id, signal),
        listImpactReports(id, signal),
      ]);
      const revisions = repo.syncStatus === "SYNCED" ? await listRevisions(id, signal) : [];
      return { repo, jobs, snapshots, reports, revisions };
    },
    2000,
    (d) =>
      d.repo.syncStatus === "PENDING" ||
      d.jobs.some((j) => isActive(j.status)) ||
      d.snapshots.some((s) => snapshotInProgress(s.status)) ||
      d.reports.some((r) => reportInProgress(r.status)),
  );

  async function act(fn: () => Promise<unknown>) {
    setActionError(null);
    try {
      await fn();
      reload();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : "Action failed");
    }
  }

  if (error && !data) {
    return (
      <main className={styles.main}>
        <Link href="/">← Repositories</Link>
        <p role="alert" className={styles.error}>{error}</p>
      </main>
    );
  }
  if (!data) {
    return (
      <main className={styles.main}>
        <p>Loading…</p>
      </main>
    );
  }

  const { repo, jobs, snapshots, reports, revisions } = data;
  const indexJobs = jobs.filter((j) => j.operation === "INDEX");
  const latestDone = indexJobs.find((j) => j.status === "SUCCEEDED");
  const activeJobId = selectedJob ?? latestDone?.id ?? null;
  const indexedCommits = new Map(indexJobs.map((j) => [j.commitHash, j]));
  const snapshotByCommit = new Map<string, Snapshot>();
  for (const s of [...snapshots].reverse()) snapshotByCommit.set(s.commitHash, s); // newest wins
  const published = snapshots.filter((s) => s.status === "PUBLISHED");
  const explored = published.find((s) => s.id === openSnapshot) ?? null;

  function graphAction(commit: string) {
    const index = indexedCommits.get(commit);
    if (!index || index.status !== "SUCCEEDED") return null;
    const snap = snapshotByCommit.get(commit);
    if (!snap) return <button onClick={() => act(() => requestGraphBuild(id, commit))}>Build graph</button>;
    if (snap.status === "PUBLISHED") return <button onClick={() => setOpenSnapshot(snap.id)}>Open graph</button>;
    if (snapshotInProgress(snap.status)) return <span className={styles.muted}>graph {snap.status.toLowerCase()}…</span>;
    return (
      <button onClick={() => act(() => requestGraphBuild(id, commit))} title={snap.failureReason ?? ""}>
        Retry graph
      </button>
    );
  }

  return (
    <main className={`${styles.main} ${styles.wide}`}>
      <Link href="/">← Repositories</Link>
      <header className={styles.header}>
        <h1>{repo.displayName}</h1>
        <p>
          <span className={`${styles.badge} ${styles["badge" + repo.syncStatus]}`}>{repo.syncStatus}</span>{" "}
          {repo.sourceType === "GIT_URL" ? repo.originUrl : "Local repository"}
          {repo.headCommit ? ` · ${repo.defaultBranch ?? "HEAD"} @ ${short(repo.headCommit)}` : ""}
        </p>
        <p>
          <button onClick={() => act(() => resync(id))}>Re-sync</button>
        </p>
      </header>

      {actionError && <p role="alert" className={styles.error}>{actionError}</p>}

      <section className={styles.card}>
        <h2>Revisions</h2>
        {repo.syncStatus === "PENDING" && <p className={styles.muted}>Syncing… revisions appear when the clone finishes.</p>}
        {repo.syncStatus === "FAILED" && (
          <p role="alert" className={styles.error}>
            Sync failed: {jobs.find((j) => j.operation === "SYNC")?.errorMessage ?? "unknown error"}
          </p>
        )}
        {revisions.length > 0 && (
          <table className={styles.table}>
            <thead>
              <tr><th>Commit</th><th>Summary</th><th>Index</th><th>Graph</th></tr>
            </thead>
            <tbody>
              {revisions.map((r) => {
                const job = indexedCommits.get(r.commitHash);
                return (
                  <tr key={r.commitHash}>
                    <td><code>{short(r.commitHash)}</code></td>
                    <td>{r.summary}</td>
                    <td>
                      {job && job.status === "SUCCEEDED" ? (
                        <button onClick={() => setSelectedJob(job.id)}>View symbols</button>
                      ) : job && isActive(job.status) ? (
                        <span className={styles.muted}>{job.status.toLowerCase()}…</span>
                      ) : (
                        <button onClick={() => act(() => requestIndex(id, r.commitHash))}>
                          {job ? "Retry index" : "Index"}
                        </button>
                      )}
                    </td>
                    <td>{graphAction(r.commitHash)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </section>

      {snapshots.length > 0 && (
        <section className={styles.card}>
          <h2>Graph snapshots</h2>
          <ul className={styles.list}>
            {snapshots.map((s) => (
              <li key={s.id} className={styles.row}>
                <span className={`${styles.badge} ${styles["badge" + (s.status === "PUBLISHED" ? "SUCCEEDED" : s.status === "FAILED" ? "FAILED" : "RUNNING")]}`}>
                  {s.status}
                </span>
                <span className={styles.label}>{short(s.commitHash)}</span>
                <span className={styles.detail}>
                  {s.status === "PUBLISHED"
                    ? `${s.nodeCount} nodes · ${s.edgeCount} edges (${s.resolvedEdgeCount} resolved, ${s.ambiguousEdgeCount} ambiguous, ${s.unresolvedEdgeCount} unresolved)`
                    : s.failureReason ?? s.builderVersion}
                </span>
                {s.status === "PUBLISHED" && <button onClick={() => setOpenSnapshot(s.id)}>Open</button>}
              </li>
            ))}
          </ul>
        </section>
      )}

      {repo.syncStatus === "SYNCED" && (
        <ImpactPanel
          repoId={id}
          revisions={revisions}
          graphed={new Set(published.map((s) => s.commitHash))}
          reports={reports}
          onRequested={reload}
        />
      )}

      {explored && <GraphExplorer key={explored.id} snapshot={explored} />}

      <section className={styles.card}>
        <h2>Jobs</h2>
        {jobs.length === 0 && <p className={styles.muted}>No jobs yet.</p>}
        <ul className={styles.list}>
          {jobs.map((j) => (
            <li key={j.id} className={styles.row}>
              <span className={`${styles.badge} ${styles["badge" + j.status]}`}>{j.status}</span>
              <span className={styles.label}>
                {j.operation}
                {j.commitHash ? ` ${short(j.commitHash)}` : ""}
              </span>
              <span className={styles.detail}>{jobSummary(j)}</span>
            </li>
          ))}
        </ul>
      </section>

      {activeJobId && <SymbolBrowser jobId={activeJobId} />}
    </main>
  );
}
