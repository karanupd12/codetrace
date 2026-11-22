"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  getRepository,
  isActive,
  listImpactReports,
  listJobs,
  listRevisions,
  listSnapshots,
  reportInProgress,
  requestGraphBuild,
  requestIndex,
  snapshotInProgress,
  type ImpactReport,
  type Job,
  type Repository,
  type Revision,
  type Snapshot,
} from "./api";
import { usePoll } from "./usePoll";

type RepoData = {
  repo: Repository;
  revisions: Revision[];
  jobs: Job[];
  snapshots: Snapshot[];
  reports: ImpactReport[];
};

export type VersionState =
  | { state: "ready"; snapshot: Snapshot; indexJob?: Job; graphJob?: Job }
  | { state: "preparing"; step: "scan" | "map"; indexJob?: Job; graphJob?: Job; snapshot?: Snapshot }
  | { state: "failed"; reason: string; indexJob?: Job; graphJob?: Job; snapshot?: Snapshot }
  | { state: "none"; indexJob?: Job };

type RepoContextValue = {
  data: RepoData | null;
  error: string | null;
  reload: () => void;
  versionOf: (commit: string) => VersionState;
  /** Scans the version and then maps its dependencies, without further clicks. */
  prepare: (commit: string) => Promise<void>;
  /** Versions being prepared from this browser (the map is requested once the scan finishes). */
  preparing: Set<string>;
  anyActive: boolean;
};

const RepoContext = createContext<RepoContextValue | null>(null);

const INTENTS_KEY = (repoId: string) => `codetrace:prepare:${repoId}`;

function readIntents(repoId: string): Set<string> {
  try {
    const raw = localStorage.getItem(INTENTS_KEY(repoId));
    return new Set(raw ? (JSON.parse(raw) as string[]) : []);
  } catch {
    return new Set();
  }
}

function writeIntents(repoId: string, intents: Set<string>) {
  try {
    localStorage.setItem(INTENTS_KEY(repoId), JSON.stringify([...intents]));
  } catch {
    /* storage unavailable: intents last for this page only */
  }
}

/** Newest first in every list the API returns. */
function latest<T>(items: T[], match: (t: T) => boolean): T | undefined {
  return items.find(match);
}

export function computeVersion(d: RepoData | null, commit: string, intended: boolean): VersionState {
  if (!d) return { state: "none" };
  const indexJob = latest(d.jobs, (j) => j.operation === "INDEX" && j.commitHash === commit);
  const graphJob = latest(d.jobs, (j) => j.operation === "GRAPH_BUILD" && j.commitHash === commit);
  const published = latest(d.snapshots, (s) => s.commitHash === commit && s.status === "PUBLISHED");
  const snapshot = latest(d.snapshots, (s) => s.commitHash === commit);
  if (published) return { state: "ready", snapshot: published, indexJob, graphJob };
  if (indexJob && isActive(indexJob.status)) return { state: "preparing", step: "scan", indexJob, graphJob, snapshot };
  if (snapshot && snapshotInProgress(snapshot.status)) return { state: "preparing", step: "map", indexJob, graphJob, snapshot };
  if (indexJob && (indexJob.status === "FAILED" || indexJob.status === "DEAD_LETTERED")) {
    return { state: "failed", reason: indexJob.errorMessage ?? "The scan failed.", indexJob, graphJob, snapshot };
  }
  if (snapshot?.status === "FAILED") {
    return { state: "failed", reason: snapshot.failureReason ?? "Mapping dependencies failed.", indexJob, graphJob, snapshot };
  }
  if (indexJob?.status === "SUCCEEDED" && intended) return { state: "preparing", step: "map", indexJob, graphJob, snapshot };
  return { state: "none", indexJob };
}

export function RepoProvider({ repoId, children }: { repoId: string; children: ReactNode }) {
  const [intents, setIntents] = useState<Set<string>>(() => new Set());
  const requested = useRef(new Set<string>()); // graph requests already sent from this page

  useEffect(() => setIntents(readIntents(repoId)), [repoId]);

  const load = useCallback(
    async (signal: AbortSignal): Promise<RepoData> => {
      const [repo, jobs, snapshots, reports] = await Promise.all([
        getRepository(repoId, signal),
        listJobs(repoId, signal),
        listSnapshots(repoId, signal),
        listImpactReports(repoId, signal),
      ]);
      const revisions = repo.syncStatus === "SYNCED" ? await listRevisions(repoId, signal) : [];
      return { repo, revisions, jobs, snapshots, reports };
    },
    [repoId],
  );

  const busy = useCallback(
    (d: RepoData) =>
      d.repo.syncStatus === "PENDING" ||
      d.jobs.some((j) => isActive(j.status)) ||
      d.snapshots.some((s) => snapshotInProgress(s.status)) ||
      d.reports.some((r) => reportInProgress(r.status)) ||
      intents.size > 0,
    [intents],
  );

  const { data, error, reload } = usePoll(load, 1500, busy);

  const updateIntents = useCallback(
    (change: (s: Set<string>) => void) => {
      setIntents((cur) => {
        const next = new Set(cur);
        change(next);
        writeIntents(repoId, next);
        return next;
      });
    },
    [repoId],
  );

  // Continue preparations: once a scan has finished, map its dependencies; forget finished or failed ones.
  useEffect(() => {
    if (!data) return;
    for (const commit of intents) {
      const v = computeVersion(data, commit, true);
      if (v.state === "ready" || v.state === "failed") {
        updateIntents((s) => s.delete(commit));
        continue;
      }
      const scanned = v.indexJob?.status === "SUCCEEDED";
      const snapshot = "snapshot" in v ? v.snapshot : undefined;
      if (scanned && !snapshot && !requested.current.has(commit)) {
        requested.current.add(commit);
        requestGraphBuild(repoId, commit).then(reload, () => updateIntents((s) => s.delete(commit)));
      }
    }
  }, [data, intents, repoId, reload, updateIntents]);

  const prepare = useCallback(
    async (commit: string) => {
      updateIntents((s) => s.add(commit));
      requested.current.delete(commit);
      const v = computeVersion(data, commit, true);
      if (v.state === "failed" && v.indexJob?.status === "SUCCEEDED") {
        requested.current.add(commit);
        await requestGraphBuild(repoId, commit); // only the map failed: retry it
      } else {
        await requestIndex(repoId, commit); // idempotent: returns the finished scan when there is one
      }
      reload();
    },
    [data, repoId, reload, updateIntents],
  );

  const value = useMemo<RepoContextValue>(
    () => ({
      data,
      error,
      reload,
      versionOf: (commit) => computeVersion(data, commit, intents.has(commit)),
      prepare,
      preparing: intents,
      anyActive: data ? busy(data) : false,
    }),
    [data, error, reload, intents, prepare, busy],
  );

  return <RepoContext.Provider value={value}>{children}</RepoContext.Provider>;
}

export function useRepo(): RepoContextValue {
  const ctx = useContext(RepoContext);
  if (!ctx) throw new Error("useRepo must be used inside RepoProvider");
  return ctx;
}
