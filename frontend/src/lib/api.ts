export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

// ---- types (mirror the backend's JSON responses) ----

export type SystemInfo = {
  name: string;
  version: string;
  role: string;
  dbMigrationVersion: string | null;
  redis: "UP" | "DOWN";
};

export type HealthComponent = { status: string };
export type Health = {
  status: string;
  components?: Record<string, HealthComponent>;
};

export type SourceType = "LOCAL" | "GIT_URL";
export type SyncStatus = "PENDING" | "SYNCED" | "FAILED";
export type JobStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "DEAD_LETTERED";

export type Repository = {
  id: string;
  sourceType: SourceType;
  displayName: string;
  originUrl: string | null;
  syncStatus: SyncStatus;
  defaultBranch: string | null;
  headCommit: string | null;
  createdAt: string;
};

export type Job = {
  id: string;
  repositoryId: string;
  operation: "SYNC" | "INDEX";
  status: JobStatus;
  commitHash: string | null;
  attempts: number;
  errorCategory: string | null;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  filesParsed: number | null;
  filesSkipped: number | null;
  symbolsExtracted: number | null;
  parserVersion: string | null;
};

export type Revision = { commitHash: string; summary: string; authorTime: string | null };

export type Page<T> = { items: T[]; limit: number; offset: number; total: number };

export type CodeSymbol = {
  path: string;
  kind: "TYPE" | "METHOD" | "FIELD";
  subtype: string;
  simpleName: string;
  qualifiedName: string;
  signature: string;
  parentQualifiedName: string;
  modifiers: string;
  startLine: number;
  endLine: number;
  extractionMethod: string;
  parserVersion: string;
  commitHash: string;
};

export type Diagnostic = {
  path: string;
  severity: string;
  code: string;
  message: string;
  line: number;
};

export const isActive = (s: JobStatus) => s === "QUEUED" || s === "RUNNING";

// ---- transport ----

async function request<T>(path: string, init?: RequestInit, signal?: AbortSignal): Promise<T> {
  const res = await fetch(`${API_BASE_URL}${path}`, {
    cache: "no-store",
    signal,
    ...init,
    headers: init?.body ? { "Content-Type": "application/json" } : undefined,
  });
  // Actuator returns 503 with a JSON body when DOWN; still parse it.
  if (!res.ok && res.status !== 503) {
    let detail = `HTTP ${res.status}`;
    try {
      const problem = (await res.json()) as { detail?: string };
      if (problem.detail) detail = problem.detail;
    } catch {
      /* non-JSON error body */
    }
    throw new Error(detail);
  }
  return (await res.json()) as T;
}

const post = <T>(path: string, body: unknown = {}) =>
  request<T>(path, { method: "POST", body: JSON.stringify(body) });

// ---- system ----

export const fetchSystemInfo = (signal?: AbortSignal) =>
  request<SystemInfo>("/api/v1/system/info", undefined, signal);

export const fetchHealth = (signal?: AbortSignal) =>
  request<Health>("/actuator/health", undefined, signal);

// ---- repositories and jobs ----

export const registerRepository = (sourceType: SourceType, location: string) =>
  post<{ repository: Repository; syncJob: Job }>("/api/v1/repositories", { sourceType, location });

export const listRepositories = (signal?: AbortSignal) =>
  request<Repository[]>("/api/v1/repositories", undefined, signal);

export const getRepository = (id: string, signal?: AbortSignal) =>
  request<Repository>(`/api/v1/repositories/${id}`, undefined, signal);

export const listRevisions = (id: string, signal?: AbortSignal) =>
  request<Revision[]>(`/api/v1/repositories/${id}/revisions`, undefined, signal);

export const listJobs = (id: string, signal?: AbortSignal) =>
  request<Job[]>(`/api/v1/repositories/${id}/index-jobs`, undefined, signal);

export const resync = (id: string) => post<Job>(`/api/v1/repositories/${id}/sync`);

export const requestIndex = (id: string, commitHash?: string) =>
  post<Job>(`/api/v1/repositories/${id}/index-jobs`, commitHash ? { commitHash } : {});

// ---- symbols ----

export const searchSymbols = (
  jobId: string,
  p: { kind?: string; q?: string; limit: number; offset: number },
  signal?: AbortSignal,
) => {
  const qs = new URLSearchParams({ limit: String(p.limit), offset: String(p.offset) });
  if (p.kind) qs.set("kind", p.kind);
  if (p.q) qs.set("q", p.q);
  return request<Page<CodeSymbol>>(`/api/v1/index-jobs/${jobId}/symbols?${qs}`, undefined, signal);
};

export const listDiagnostics = (jobId: string, limit: number, signal?: AbortSignal) =>
  request<Page<Diagnostic>>(`/api/v1/index-jobs/${jobId}/diagnostics?limit=${limit}`, undefined, signal);
