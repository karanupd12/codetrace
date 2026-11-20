"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import {
  getRepository,
  isActive,
  listJobs,
  listRevisions,
  requestIndex,
  resync,
  type Job,
} from "@/lib/api";
import { usePoll } from "@/lib/usePoll";
import styles from "../../page.module.css";
import SymbolBrowser from "./SymbolBrowser";

const short = (h: string | null) => (h ? h.slice(0, 7) : "—");

function jobSummary(j: Job) {
  if (j.status === "SUCCEEDED" && j.operation === "INDEX") {
    return `${j.filesParsed} files parsed · ${j.filesSkipped} skipped · ${j.symbolsExtracted} symbols`;
  }
  if (j.errorCategory) return `${j.errorCategory}: ${j.errorMessage ?? ""}`;
  return `attempt ${j.attempts}`;
}

export default function RepoPage() {
  const { id } = useParams<{ id: string }>();
  const [actionError, setActionError] = useState<string | null>(null);
  const [selectedJob, setSelectedJob] = useState<string | null>(null);

  const { data, error, reload } = usePoll(
    async (signal) => {
      const [repo, jobs] = await Promise.all([getRepository(id, signal), listJobs(id, signal)]);
      const revisions = repo.syncStatus === "SYNCED" ? await listRevisions(id, signal) : [];
      return { repo, jobs, revisions };
    },
    2000,
    (d) => d.repo.syncStatus === "PENDING" || d.jobs.some((j) => isActive(j.status)),
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

  const { repo, jobs, revisions } = data;
  const indexJobs = jobs.filter((j) => j.operation === "INDEX");
  const latestDone = indexJobs.find((j) => j.status === "SUCCEEDED");
  const activeJobId = selectedJob ?? latestDone?.id ?? null;
  const indexedCommits = new Map(indexJobs.map((j) => [j.commitHash, j]));

  return (
    <main className={styles.main}>
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
              <tr><th>Commit</th><th>Summary</th><th /></tr>
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
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </section>

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
