"use client";

import { useEffect, useState } from "react";
import {
  getSubgraph,
  searchGraphNodes,
  type EdgeType,
  type GraphNode,
  type Resolution,
  type Snapshot,
  type Subgraph,
} from "@/lib/api";
import GraphCanvas, { type Selection } from "./GraphCanvas";
import styles from "./graph.module.css";
import page from "../../page.module.css";

const EDGE_TYPES: EdgeType[] = ["CALLS", "REFERENCES", "EXTENDS", "IMPLEMENTS", "IMPORTS", "CONTAINS"];
const DEFAULT_TYPES: EdgeType[] = ["CALLS", "REFERENCES", "EXTENDS", "IMPLEMENTS"];

function pct(part: number | null, total: number | null) {
  return part == null || !total ? "–" : `${Math.round((part / total) * 100)}%`;
}

function Detail({ sel, onFocus }: { sel: Selection; onFocus: (id: string) => void }) {
  if (!sel) {
    return <p className={styles.counts}>Click a node or edge for details. Double-click a node to centre the graph on it.</p>;
  }
  if (sel.kind === "external") {
    return (
      <div className={styles.panel}>
        <strong>Unresolved reference</strong>
        <span>
          <code>{sel.name}</code> is not declared in this repository (an external library, or something the
          resolver could not prove).
        </span>
      </div>
    );
  }
  if (sel.kind === "node") {
    const n = sel.node;
    return (
      <div className={styles.panel}>
        <strong>{n.qualifiedName}</strong>
        <dl>
          <dt>Kind</dt><dd>{n.nodeType}{n.subtype ? ` · ${n.subtype}` : ""}</dd>
          <dt>Location</dt><dd>{n.path ? `${n.path}:${n.startLine}-${n.endLine}` : "—"}</dd>
          {n.modifiers && (<><dt>Modifiers</dt><dd>{n.modifiers}</dd></>)}
          <dt>Extraction</dt><dd>{n.extractionMethod} · {n.parserVersion}</dd>
        </dl>
        <p><button onClick={() => onFocus(n.id)}>Centre graph here</button></p>
      </div>
    );
  }
  const e = sel.edge;
  return (
    <div className={styles.panel}>
      <strong>{e.srcName} → {e.dstName}</strong>
      <dl>
        <dt>Type</dt><dd>{e.edgeType}</dd>
        <dt>Resolution</dt><dd>{e.resolution}{e.reason ? ` (${e.reason})` : ""}</dd>
        <dt>Confidence</dt><dd>{e.confidence.toFixed(2)}</dd>
        <dt>Evidence</dt><dd>{e.evidenceKind}</dd>
        <dt>Source location</dt><dd>{e.evidencePath}:{e.evidenceLine}{e.occurrences > 1 ? ` (+${e.occurrences - 1} more sites)` : ""}</dd>
        {e.alternativeGroup && (<><dt>Alternatives</dt><dd>one of several candidates (group {e.alternativeGroup.slice(0, 8)})</dd></>)}
        <dt>Produced by</dt><dd>{e.resolverVersion} · {e.extractionMethod}</dd>
      </dl>
    </div>
  );
}

export default function GraphExplorer({ snapshot }: { snapshot: Snapshot }) {
  const [type, setType] = useState("TYPE");
  const [q, setQ] = useState("");
  const [results, setResults] = useState<GraphNode[]>([]);
  const [total, setTotal] = useState(0);
  const [center, setCenter] = useState<string | null>(null);
  const [depth, setDepth] = useState(1);
  const [edgeTypes, setEdgeTypes] = useState<EdgeType[]>(DEFAULT_TYPES);
  const [showAmbiguous, setShowAmbiguous] = useState(true);
  const [showUnresolved, setShowUnresolved] = useState(false);
  const [sub, setSub] = useState<Subgraph | null>(null);
  const [selection, setSelection] = useState<Selection>(null);
  const [error, setError] = useState<string | null>(null);

  // node search (debounced); the first hit becomes the initial centre
  useEffect(() => {
    const ctrl = new AbortController();
    const t = setTimeout(() => {
      searchGraphNodes(snapshot.id, { type: type || undefined, q: q.trim() || undefined, limit: 50, offset: 0 }, ctrl.signal)
        .then((p) => {
          setResults(p.items);
          setTotal(p.total);
          setError(null);
          setCenter((c) => c ?? p.items[0]?.id ?? null);
        })
        .catch((e: unknown) => {
          if (!ctrl.signal.aborted) setError(e instanceof Error ? e.message : "Search failed");
        });
    }, 200);
    return () => {
      ctrl.abort();
      clearTimeout(t);
    };
  }, [snapshot.id, type, q]);

  // neighbourhood of the centre
  useEffect(() => {
    if (!center) return;
    const ctrl = new AbortController();
    const states: Resolution[] = ["RESOLVED", ...(showAmbiguous ? ["AMBIGUOUS" as const] : []), ...(showUnresolved ? ["UNRESOLVED" as const] : [])];
    getSubgraph(snapshot.id, { center, depth, types: edgeTypes, states, limit: 200 }, ctrl.signal)
      .then((s) => {
        setSub(s);
        setError(null);
      })
      .catch((e: unknown) => {
        if (!ctrl.signal.aborted) setError(e instanceof Error ? e.message : "Could not load graph");
      });
    return () => ctrl.abort();
  }, [snapshot.id, center, depth, edgeTypes, showAmbiguous, showUnresolved]);

  const toggleType = (t: EdgeType) =>
    setEdgeTypes((cur) => (cur.includes(t) ? cur.filter((x) => x !== t) : [...cur, t]));

  const focus = (id: string) => {
    setCenter(id);
    setSelection(null);
  };

  return (
    <section className={page.card}>
      <h2>Dependency graph</h2>
      <p className={styles.counts}>
        Snapshot {snapshot.commitHash.slice(0, 7)} · {snapshot.nodeCount} nodes · {snapshot.edgeCount} edges ·{" "}
        {pct(snapshot.resolvedEdgeCount, snapshot.edgeCount)} resolved,{" "}
        {pct(snapshot.ambiguousEdgeCount, snapshot.edgeCount)} ambiguous,{" "}
        {pct(snapshot.unresolvedEdgeCount, snapshot.edgeCount)} unresolved · built by {snapshot.builderVersion}
      </p>
      {error && <p role="alert" className={page.error}>{error}</p>}

      <div className={styles.explorer}>
        <div className={styles.side}>
          <select aria-label="Node kind" value={type} onChange={(e) => setType(e.target.value)}>
            <option value="TYPE">Types</option>
            <option value="METHOD">Methods</option>
            <option value="FIELD">Fields</option>
            <option value="FILE">Files</option>
            <option value="PACKAGE">Packages</option>
            <option value="">All kinds</option>
          </select>
          <input
            type="search"
            placeholder="Search by qualified name"
            aria-label="Search nodes"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
          <ul className={styles.results} aria-label="Matching nodes">
            {results.map((n) => (
              <li key={n.id}>
                <button aria-current={n.id === center} onClick={() => focus(n.id)} title={n.path}>
                  {n.qualifiedName}
                </button>
              </li>
            ))}
            {results.length === 0 && <li className={styles.counts} style={{ padding: 8 }}>No matches.</li>}
          </ul>
          <span className={styles.counts}>{total > results.length ? `Showing ${results.length} of ${total}` : `${total} match(es)`}</span>
        </div>

        <div className={styles.main}>
          <div className={styles.controls}>
            <label>
              Depth
              <select value={depth} onChange={(e) => setDepth(Number(e.target.value))} aria-label="Depth">
                {[1, 2, 3, 4].map((d) => <option key={d} value={d}>{d}</option>)}
              </select>
            </label>
            {EDGE_TYPES.map((t) => (
              <label key={t}>
                <input type="checkbox" checked={edgeTypes.includes(t)} onChange={() => toggleType(t)} />
                {t.toLowerCase()}
              </label>
            ))}
            <label><input type="checkbox" checked={showAmbiguous} onChange={(e) => setShowAmbiguous(e.target.checked)} />ambiguous</label>
            <label><input type="checkbox" checked={showUnresolved} onChange={(e) => setShowUnresolved(e.target.checked)} />unresolved</label>
          </div>

          {sub ? (
            <>
              {sub.truncated && (
                <p className={styles.warn}>Showing the first {sub.edges.length} edges; narrow the filters or reduce the depth to see the rest.</p>
              )}
              {sub.edges.length === 0 && <p className={styles.counts}>No edges match these filters around this node.</p>}
              <GraphCanvas
                subgraph={sub}
                onSelect={(s, recenter) => (recenter ? focus(recenter) : setSelection(s))}
              />
            </>
          ) : (
            <p className={styles.counts}>{center ? "Loading graph…" : "Search for a node to start."}</p>
          )}

          <div className={styles.legend}>
            <span><span className={styles.swatch} />resolved (evidence supports one target)</span>
            <span><span className={`${styles.swatch} ${styles.swatchAmbiguous}`} />ambiguous (several candidates)</span>
            <span><span className={`${styles.swatch} ${styles.swatchUnresolved}`} />unresolved (not in repository / unknown)</span>
          </div>
          <Detail sel={selection} onFocus={focus} />
        </div>
      </div>
    </section>
  );
}
