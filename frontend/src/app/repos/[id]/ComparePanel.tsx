"use client";

import { useEffect, useState } from "react";
import {
  compareSnapshots,
  listEdgeChanges,
  listNodeChanges,
  type ChangeCount,
  type EdgeChange,
  type NodeChange,
  type Revision,
  type Snapshot,
  type SnapshotComparison,
} from "@/lib/api";
import page from "../../page.module.css";
import styles from "./impact.module.css";

const short = (h: string) => h.slice(0, 7);
const PAGE = 50;

/** Counts per type as rows, with one column per change kind. */
function CountTable({ title, counts, changes }: { title: string; counts: ChangeCount[]; changes: string[] }) {
  const kinds = [...new Set(counts.map((c) => c.kind))].sort();
  const count = (kind: string, change: string) =>
    counts.find((c) => c.kind === kind && c.change === change)?.count ?? 0;
  if (kinds.length === 0) return <p className={page.muted}>No {title.toLowerCase()} changed.</p>;
  return (
    <table className={styles.factors}>
      <thead>
        <tr>
          <th>{title}</th>
          {changes.map((c) => <th key={c} className={styles.num}>{c.toLowerCase()}</th>)}
        </tr>
      </thead>
      <tbody>
        {kinds.map((k) => (
          <tr key={k}>
            <td>{k}</td>
            {changes.map((c) => <td key={c} className={styles.num}>{count(k, c)}</td>)}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function nodeDetail(n: NodeChange) {
  if (n.change !== "CHANGED") return n.path;
  const parts = [];
  if (n.baseModifiers !== n.headModifiers) parts.push(`modifiers "${n.baseModifiers}" → "${n.headModifiers}"`);
  if (n.baseSubtype !== n.headSubtype) parts.push(`${n.baseSubtype} → ${n.headSubtype}`);
  return parts.join(" · ");
}

/** Pick two published snapshots and list how the dependency graph changed between them. */
export default function ComparePanel({ snapshots, revisions }: { snapshots: Snapshot[]; revisions: Revision[] }) {
  const summaryOf = new Map(revisions.map((r) => [r.commitHash, r.summary]));
  const [base, setBase] = useState("");
  const [head, setHead] = useState("");
  const [result, setResult] = useState<SnapshotComparison | null>(null);
  const [nodeFilter, setNodeFilter] = useState("");
  const [edgeFilter, setEdgeFilter] = useState("");
  const [nodes, setNodes] = useState<{ items: NodeChange[]; total: number }>({ items: [], total: 0 });
  const [edges, setEdges] = useState<{ items: EdgeChange[]; total: number }>({ items: [], total: 0 });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // defaults: the newest published snapshot against the one before it
  useEffect(() => {
    if (!head && snapshots[0]) setHead(snapshots[0].id);
    if (!base && snapshots[1]) setBase(snapshots[1].id);
  }, [snapshots, head, base]);

  useEffect(() => {
    if (!result) return;
    const ctrl = new AbortController();
    listNodeChanges(result.base.id, result.head.id, { change: nodeFilter, limit: PAGE }, ctrl.signal)
      .then((p) => setNodes({ items: p.items, total: p.total }))
      .catch((e) => !ctrl.signal.aborted && setError(e instanceof Error ? e.message : "Could not load node changes"));
    return () => ctrl.abort();
  }, [result, nodeFilter]);

  useEffect(() => {
    if (!result) return;
    const ctrl = new AbortController();
    listEdgeChanges(result.base.id, result.head.id, { change: edgeFilter, types: "CALLS,REFERENCES,EXTENDS,IMPLEMENTS", limit: PAGE }, ctrl.signal)
      .then((p) => setEdges({ items: p.items, total: p.total }))
      .catch((e) => !ctrl.signal.aborted && setError(e instanceof Error ? e.message : "Could not load edge changes"));
    return () => ctrl.abort();
  }, [result, edgeFilter]);

  async function compare() {
    setError(null);
    setBusy(true);
    try {
      setResult(await compareSnapshots(base, head));
    } catch (e) {
      setResult(null);
      setError(e instanceof Error ? e.message : "Could not compare the snapshots");
    } finally {
      setBusy(false);
    }
  }

  async function moreNodes() {
    if (!result) return;
    const p = await listNodeChanges(result.base.id, result.head.id, { change: nodeFilter, limit: PAGE, offset: nodes.items.length });
    setNodes({ items: [...nodes.items, ...p.items], total: p.total });
  }

  async function moreEdges() {
    if (!result) return;
    const p = await listEdgeChanges(result.base.id, result.head.id, {
      change: edgeFilter, types: "CALLS,REFERENCES,EXTENDS,IMPLEMENTS", limit: PAGE, offset: edges.items.length,
    });
    setEdges({ items: [...edges.items, ...p.items], total: p.total });
  }

  const label = (s: Snapshot) =>
    `${short(s.commitHash)} · ${(summaryOf.get(s.commitHash) ?? "").slice(0, 50)} (${s.builderVersion})`;

  return (
    <section className={page.card}>
      <h2>Compare snapshots</h2>
      {snapshots.length < 2 ? (
        <p className={page.muted}>Publish graphs for at least two revisions to compare them.</p>
      ) : (
        <div className={styles.form}>
          <label>
            Base (before)
            <select value={base} onChange={(e) => setBase(e.target.value)}>
              {snapshots.map((s) => <option key={s.id} value={s.id}>{label(s)}</option>)}
            </select>
          </label>
          <label>
            Head (after)
            <select value={head} onChange={(e) => setHead(e.target.value)}>
              {snapshots.map((s) => <option key={s.id} value={s.id}>{label(s)}</option>)}
            </select>
          </label>
          <button onClick={compare} disabled={busy || !base || !head || base === head}>
            {busy ? "Comparing…" : "Compare"}
          </button>
        </div>
      )}
      {error && <p role="alert" className={page.error}>{error}</p>}

      {result && (
        <div className={styles.section}>
          <p className={styles.notice}>
            {short(result.base.commitHash)} → {short(result.head.commitHash)}. Nodes are matched by type, path, name and
            signature, edges by both endpoints, type and resolution, so a moved file shows as removed and added.
          </p>
          <CountTable title="Nodes" counts={result.nodes} changes={["ADDED", "REMOVED", "CHANGED"]} />
          <CountTable title="Edges" counts={result.edges} changes={["ADDED", "REMOVED"]} />

          <h3>Node changes ({nodes.total})</h3>
          <div className={styles.form}>
            <label>
              Show
              <select value={nodeFilter} onChange={(e) => setNodeFilter(e.target.value)}>
                <option value="">all</option>
                <option value="ADDED">added</option>
                <option value="REMOVED">removed</option>
                <option value="CHANGED">changed</option>
              </select>
            </label>
          </div>
          <table className={styles.factors}>
            <tbody>
              {nodes.items.map((n) => (
                <tr key={`${n.change}|${n.nodeType}|${n.path}|${n.qualifiedName}|${n.signature}`}>
                  <td>{n.change.toLowerCase()}</td>
                  <td>{n.nodeType}</td>
                  <td><code>{n.qualifiedName}</code></td>
                  <td className={page.muted}>{nodeDetail(n)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {nodes.items.length < nodes.total && <button onClick={moreNodes}>Show more</button>}

          <h3>Dependency changes ({edges.total})</h3>
          <div className={styles.form}>
            <label>
              Show
              <select value={edgeFilter} onChange={(e) => setEdgeFilter(e.target.value)}>
                <option value="">all</option>
                <option value="ADDED">added</option>
                <option value="REMOVED">removed</option>
              </select>
            </label>
          </div>
          <table className={styles.factors}>
            <tbody>
              {edges.items.map((e, i) => (
                <tr key={`${i}|${e.change}|${e.srcName}|${e.edgeType}|${e.dstName}|${e.resolutionState}`}>
                  <td>{e.change.toLowerCase()}</td>
                  <td><code>{e.srcName}</code></td>
                  <td>{e.edgeType.toLowerCase()}</td>
                  <td><code>{e.dstName}</code></td>
                  <td className={page.muted}>
                    {e.resolutionState.toLowerCase()} · {e.evidencePath}:{e.evidenceLine}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {edges.items.length < edges.total && <button onClick={moreEdges}>Show more</button>}
        </div>
      )}
    </section>
  );
}
