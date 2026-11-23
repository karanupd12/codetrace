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

/** A colour family from the design tokens: categories (identity) and status. */
export type Hue = "blue" | "teal" | "violet" | "pink" | "green" | "yellow" | "orange" | "red" | "neutral";

/** Colour of each kind of code, the same everywhere (map, lists, chips). */
export const KIND_HUE: Record<string, Hue> = {
  TYPE: "blue",
  METHOD: "teal",
  FIELD: "yellow",
  TEST: "violet",
  ENDPOINT: "pink",
  FILE: "neutral",
  PACKAGE: "neutral",
};

/** Colour of each kind of background task. */
export const JOB_HUE: Record<Job["operation"], Hue> = {
  SYNC: "blue",
  INDEX: "violet",
  GRAPH_BUILD: "teal",
  IMPACT: "orange",
};

/** Colour of an HTTP method pill. */
export function methodHue(method: string): Hue {
  switch (method) {
    case "GET":
      return "blue";
    case "POST":
      return "green";
    case "PUT":
    case "PATCH":
      return "yellow";
    case "DELETE":
      return "red";
    default:
      return "neutral";
  }
}

/** The kind of a finding's symbol, with tests and endpoints as their own kinds. */
export function findingKind(nodeType: string, test: boolean): string {
  if (nodeType === "ENDPOINT") return "ENDPOINT";
  if (test) return "TEST";
  return nodeType || "METHOD";
}
