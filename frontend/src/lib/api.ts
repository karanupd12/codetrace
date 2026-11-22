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
  operation: "SYNC" | "INDEX" | "GRAPH_BUILD" | "IMPACT";
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
  filesReused: number | null;
  baseJobId: string | null;
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

// ---- dependency graph ----

export type SnapshotStatus = "REQUESTED" | "BUILDING" | "VALIDATING" | "PUBLISHED" | "FAILED";
export type Resolution = "RESOLVED" | "AMBIGUOUS" | "UNRESOLVED";
export type EdgeType = "CONTAINS" | "CALLS" | "REFERENCES" | "EXTENDS" | "IMPLEMENTS" | "IMPORTS" | "TESTS";
export type NodeType = "FILE" | "PACKAGE" | "TYPE" | "METHOD" | "FIELD";

export type Snapshot = {
  id: string;
  repositoryId: string;
  commitHash: string;
  parserVersion: string;
  builderVersion: string;
  status: SnapshotStatus;
  nodeCount: number | null;
  edgeCount: number | null;
  resolvedEdgeCount: number | null;
  ambiguousEdgeCount: number | null;
  unresolvedEdgeCount: number | null;
  failureReason: string | null;
  createdAt: string;
  publishedAt: string | null;
};

export const snapshotInProgress = (s: SnapshotStatus) =>
  s === "REQUESTED" || s === "BUILDING" || s === "VALIDATING";

export type GraphNode = {
  id: string;
  nodeType: NodeType;
  qualifiedName: string;
  signature: string;
  simpleName: string;
  subtype: string;
  path: string;
  startLine: number;
  endLine: number;
  modifiers: string;
  extractionMethod: string;
  parserVersion: string;
};

/** An edge with its provenance. `dstId` is null for UNRESOLVED edges; `dstName` says what was being looked for. */
export type GraphEdge = {
  id: number;
  srcId: string;
  srcName: string;
  dstId: string | null;
  dstName: string;
  edgeType: EdgeType;
  resolution: Resolution;
  confidence: number;
  evidenceKind: string;
  reason: string;
  alternativeGroup: string;
  evidencePath: string;
  evidenceLine: number;
  occurrences: number;
  extractionMethod: string;
  resolverVersion: string;
};

export type Subgraph = {
  center: string;
  depth: number;
  nodes: GraphNode[];
  edges: GraphEdge[];
  truncated: boolean;
};

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

// ---- graph snapshots ----

export const requestGraphBuild = (repoId: string, commitHash?: string) =>
  post<{ snapshot: Snapshot; job: Job }>(
    `/api/v1/repositories/${repoId}/graph-snapshots`,
    commitHash ? { commitHash } : {},
  );

export const listSnapshots = (repoId: string, signal?: AbortSignal) =>
  request<Snapshot[]>(`/api/v1/repositories/${repoId}/snapshots`, undefined, signal);

export const searchGraphNodes = (
  snapshotId: string,
  p: { type?: string; q?: string; limit: number; offset: number },
  signal?: AbortSignal,
) => {
  const qs = new URLSearchParams({ limit: String(p.limit), offset: String(p.offset) });
  if (p.type) qs.set("type", p.type);
  if (p.q) qs.set("q", p.q);
  return request<Page<GraphNode>>(`/api/v1/snapshots/${snapshotId}/nodes?${qs}`, undefined, signal);
};

export type EdgeDegree = { edgeType: EdgeType; resolution: Resolution; count: number };
export type NodeDetail = { node: GraphNode; outgoing: EdgeDegree[]; incoming: EdgeDegree[] };

export const getNodeDetail = (snapshotId: string, nodeId: string, signal?: AbortSignal) =>
  request<NodeDetail>(`/api/v1/snapshots/${snapshotId}/nodes/${nodeId}`, undefined, signal);

export const getSubgraph = (
  snapshotId: string,
  p: { center: string; depth: number; types: EdgeType[]; states: Resolution[]; limit: number },
  signal?: AbortSignal,
) => {
  const qs = new URLSearchParams({
    center: p.center,
    depth: String(p.depth),
    types: p.types.join(","),
    states: p.states.join(","),
    limit: String(p.limit),
  });
  return request<Subgraph>(`/api/v1/snapshots/${snapshotId}/graph?${qs}`, undefined, signal);
};

// ---- change impact ----

export type ReportStatus = "REQUESTED" | "RUNNING" | "COMPLETED" | "FAILED";
export type RiskLevel = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
export type RiskFactor = { signal: string; points: number; maxPoints: number; reason: string };

export type ImpactReport = {
  id: string;
  repositoryId: string;
  baseCommit: string;
  headCommit: string;
  baseSnapshotId: string;
  headSnapshotId: string;
  algorithmVersion: string;
  depth: number;
  status: ReportStatus;
  riskScore: number | null;
  riskLevel: RiskLevel | null;
  riskFactors: RiskFactor[];
  changedCount: number | null;
  affectedCount: number | null;
  testsAffectedCount: number | null;
  possibleCount: number | null;
  unmappedHunks: number | null;
  nonJavaFiles: number | null;
  truncated: boolean | null;
  failureReason: string | null;
  createdAt: string;
  completedAt: string | null;
};

export type Hop = {
  from: string;
  to: string;
  edgeType: EdgeType;
  resolution: Resolution;
  confidence: number;
  path: string;
  line: number;
};

export type FindingType = "CHANGED" | "AFFECTED" | "POSSIBLE" | "ENDPOINT";

export type Finding = {
  findingType: FindingType;
  ordinal: number;
  name: string;
  nodeType: string;
  path: string;
  line: number;
  module: string;
  test: boolean;
  changeKind: "ADDED" | "MODIFIED" | "REMOVED" | null;
  directDependents: number | null;
  distance: number | null;
  certain: boolean | null;
  confidence: number | null;
  changedSymbol: string;
  chain: Hop[];
};

export const reportInProgress = (s: ReportStatus) => s === "REQUESTED" || s === "RUNNING";

export const requestImpactReport = (repoId: string, baseCommit: string, headCommit: string, depth?: number) =>
  post<{ report: ImpactReport; job: Job }>(`/api/v1/repositories/${repoId}/impact-reports`, {
    baseCommit,
    headCommit,
    ...(depth ? { depth } : {}),
  });

export const listImpactReports = (repoId: string, signal?: AbortSignal) =>
  request<ImpactReport[]>(`/api/v1/repositories/${repoId}/impact-reports`, undefined, signal);

export const listFindings = (
  reportId: string,
  p: { type?: FindingType; module?: string; limit: number; offset: number },
  signal?: AbortSignal,
) => {
  const qs = new URLSearchParams({ limit: String(p.limit), offset: String(p.offset) });
  if (p.type) qs.set("type", p.type);
  if (p.module) qs.set("module", p.module);
  return request<Page<Finding>>(`/api/v1/impact-reports/${reportId}/findings?${qs}`, undefined, signal);
};

export const reportMarkdownUrl = (reportId: string) => `${API_BASE_URL}/api/v1/impact-reports/${reportId}/report.md`;

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

// ---- snapshot comparison ----

export type ChangeCount = { change: "ADDED" | "REMOVED" | "CHANGED"; kind: string; count: number };

export type SnapshotComparison = { base: Snapshot; head: Snapshot; nodes: ChangeCount[]; edges: ChangeCount[] };

export type NodeChange = {
  change: "ADDED" | "REMOVED" | "CHANGED";
  nodeType: string;
  qualifiedName: string;
  signature: string;
  path: string;
  baseNodeId: string | null;
  headNodeId: string | null;
  baseSubtype: string | null;
  headSubtype: string | null;
  baseModifiers: string | null;
  headModifiers: string | null;
};

export type EdgeChange = {
  change: "ADDED" | "REMOVED";
  edgeType: string;
  resolutionState: string;
  confidence: number;
  srcType: string;
  srcName: string;
  srcSignature: string;
  dstType: string;
  dstName: string;
  dstSignature: string;
  evidencePath: string;
  evidenceLine: number;
};

const comparePath = (base: string, head: string) => `/api/v1/snapshots/${base}/compare/${head}`;

export const compareSnapshots = (base: string, head: string, signal?: AbortSignal) =>
  request<SnapshotComparison>(comparePath(base, head), undefined, signal);

export const listNodeChanges = (
  base: string,
  head: string,
  opts: { change?: string; type?: string; limit?: number; offset?: number },
  signal?: AbortSignal,
) => {
  const q = new URLSearchParams();
  if (opts.change) q.set("change", opts.change);
  if (opts.type) q.set("type", opts.type);
  q.set("limit", String(opts.limit ?? 50));
  q.set("offset", String(opts.offset ?? 0));
  return request<Page<NodeChange>>(`${comparePath(base, head)}/nodes?${q}`, undefined, signal);
};

export const listEdgeChanges = (
  base: string,
  head: string,
  opts: { change?: string; types?: string; limit?: number; offset?: number },
  signal?: AbortSignal,
) => {
  const q = new URLSearchParams();
  if (opts.change) q.set("change", opts.change);
  if (opts.types) q.set("types", opts.types);
  q.set("limit", String(opts.limit ?? 50));
  q.set("offset", String(opts.offset ?? 0));
  return request<Page<EdgeChange>>(`${comparePath(base, head)}/edges?${q}`, undefined, signal);
};

// ---- HTTP endpoints ----

export type Endpoint = {
  httpMethod: string;
  route: string;
  handlerQualifiedName: string;
  path: string;
  line: number;
  framework: "SPRING_MVC" | "JAX_RS";
  reason: string;
};

export const listEndpoints = (jobId: string, limit: number, signal?: AbortSignal) =>
  request<Page<Endpoint>>(`/api/v1/index-jobs/${jobId}/endpoints?limit=${limit}`, undefined, signal);
