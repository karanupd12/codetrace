import type { EdgeType, Job, NodeType } from "./api";

/** What each background task is called on screen. */
export const JOB_TITLE: Record<Job["operation"], string> = {
  SYNC: "Checking for new versions",
  INDEX: "Scanning the code",
  GRAPH_BUILD: "Mapping dependencies",
  IMPACT: "Analyzing the change",
};

export const KIND_WORD: Record<NodeType, string> = {
  TYPE: "class",
  METHOD: "method",
  FIELD: "field",
  FILE: "file",
  PACKAGE: "package",
};

/** "A calls B", "A uses B" … read left to right. */
export const EDGE_WORD: Record<EdgeType, string> = {
  CALLS: "calls",
  REFERENCES: "uses",
  EXTENDS: "extends",
  IMPLEMENTS: "implements",
  IMPORTS: "imports",
  CONTAINS: "contains",
  TESTS: "tests",
  OVERRIDES: "overrides",
  OVERRIDDEN_BY: "may run",
};

/** `com.acme.shop.Order#price(double)` → `Order.price(double)`; `com.acme.shop.Order` → `Order`. */
export function shortName(qualifiedName: string): string {
  const [type, member] = qualifiedName.split("#");
  const simpleType = type.slice(type.lastIndexOf(".") + 1);
  if (member === undefined) return simpleType;
  return member.startsWith("<init>") ? `new ${simpleType}${member.slice(6)}` : `${simpleType}.${member}`;
}

/** The package part of a qualified name, for secondary text. */
export function packageOf(qualifiedName: string): string {
  const type = qualifiedName.split("#")[0];
  const i = type.lastIndexOf(".");
  return i < 0 ? "" : type.slice(0, i);
}

export function fileName(path: string): string {
  return path.slice(path.lastIndexOf("/") + 1);
}

export function duration(ms: number): string {
  if (ms < 1000) return `${Math.max(0, Math.round(ms))} ms`;
  const s = ms / 1000;
  if (s < 60) return `${s < 10 ? s.toFixed(1) : Math.round(s)} s`;
  const m = Math.floor(s / 60);
  return `${m} min ${Math.round(s - m * 60)} s`;
}

export function plural(n: number, one: string, many = `${one}s`): string {
  return `${n.toLocaleString()} ${n === 1 ? one : many}`;
}

/** A one-line, plain description of what a finished job did. */
export function jobSummary(j: Job): string {
  if (j.status === "FAILED" || j.status === "DEAD_LETTERED") return j.errorMessage ?? "Failed";
  if (j.status === "QUEUED") return "Waiting to start";
  if (j.status === "RUNNING") return "In progress";
  if (j.operation === "INDEX" && j.filesParsed != null) {
    const reused = j.filesReused ? ` (${j.filesReused.toLocaleString()} unchanged, reused)` : "";
    return `Scanned ${plural(j.filesParsed + (j.filesSkipped ?? 0), "file")}${reused}`;
  }
  return "Done";
}
