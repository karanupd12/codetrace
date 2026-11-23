"use client";

import { useEffect, useMemo, useState } from "react";
import {
  getNodeDetail,
  getSubgraph,
  listNodeEdges,
  searchGraphNodes,
  type EdgeType,
  type GraphEdge,
  type GraphNode,
  type NodeDetail,
  type Resolution,
  type Snapshot,
  type Subgraph,
} from "@/lib/api";
import { EDGE_WORD, KIND_WORD, fileName, kindOfName, nodeKind, packageOf, plural, shortName } from "@/lib/words";
import { Banner, Button, Details, Help, Segmented, Skeleton, Spinner, controlClass } from "@/components/ui";
import { KindChip } from "@/components/viz";
import GraphCanvas, { type Selection } from "./GraphCanvas";
import s from "./explore.module.css";

const LINK_TYPES: { type: EdgeType; label: string }[] = [
  { type: "CALLS", label: "calls" },
  { type: "REFERENCES", label: "uses" },
  { type: "EXTENDS", label: "extends" },
  { type: "IMPLEMENTS", label: "implements" },
  { type: "OVERRIDES", label: "overrides" },
  { type: "TESTS", label: "tests" },
  { type: "IMPORTS", label: "imports" },
];
const DEFAULT_TYPES: EdgeType[] = ["CALLS", "REFERENCES", "EXTENDS", "IMPLEMENTS", "OVERRIDES", "TESTS"];
const DEPENDENCY_TYPES = "CALLS,REFERENCES,EXTENDS,IMPLEMENTS,OVERRIDES,TESTS";
const FIRST_LIMIT = 40;
const CONNECTION_PAGE = 25;

function dependencyCount(d: NodeDetail, dir: "in" | "out") {
  return (dir === "in" ? d.incoming : d.outgoing)
    .filter((x) => x.edgeType !== "CONTAINS" && x.edgeType !== "IMPORTS")
    .reduce((sum, x) => sum + x.count, 0);
}

function Connections({ snapshotId, node, onFocus }: { snapshotId: string; node: GraphNode; onFocus: (id: string) => void }) {
  const [dir, setDir] = useState<"IN" | "OUT">("IN");
  const [items, setItems] = useState<GraphEdge[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const ctrl = new AbortController();
    setTotal(null);
    listNodeEdges(snapshotId, node.id, { direction: dir, types: DEPENDENCY_TYPES, limit: CONNECTION_PAGE, offset: 0 }, ctrl.signal)
      .then((p) => {
        setItems(p.items);
        setTotal(p.total);
      })
      .catch(() => !ctrl.signal.aborted && setTotal(0));
    return () => ctrl.abort();
  }, [snapshotId, node.id, dir]);

  const more = () => {
    setLoading(true);
    listNodeEdges(snapshotId, node.id, { direction: dir, types: DEPENDENCY_TYPES, limit: CONNECTION_PAGE, offset: items.length })
      .then((p) => setItems((cur) => [...cur, ...p.items]))
      .finally(() => setLoading(false));
  };

  return (
    <div style={{ display: "grid", gap: 8 }}>
      <Segmented
        label="Direction"
        value={dir}
        onChange={setDir}
        options={[
          { value: "IN", label: "Used by" },
          { value: "OUT", label: "Uses" },
        ]}
      />
      {total === null ? (
        <Skeleton lines={3} />
      ) : total === 0 ? (
        <p className={s.detailMeta}>{dir === "IN" ? "Nothing in this repository uses it." : "It uses nothing else in this repository."}</p>
      ) : (
        <>
          <ul className={s.connections}>
            {items.map((e) => {
              const otherId = dir === "IN" ? e.srcId : e.dstId;
              const otherName = dir === "IN" ? e.srcName : e.dstName;
              return (
                <li key={e.id}>
                  <span>
                    {dir === "IN" ? "" : `${EDGE_WORD[e.edgeType] ?? e.edgeType} `}
                    {otherId ? (
                      <button type="button" onClick={() => onFocus(otherId)} title="Show on the map">
                        <span className={`${s.kindDot} ${s[`kind${kindOfName(otherName, dir === "IN" ? e.evidencePath : undefined)}`]}`} aria-hidden />
                        {shortName(otherName)}
                      </button>
                    ) : (
                      <code>{otherName}</code>
                    )}
                    {dir === "IN" ? ` ${EDGE_WORD[e.edgeType] ?? e.edgeType} it` : ""}
                  </span>
                  <span className={s.connWhere}>
                    {fileName(e.evidencePath)}:{e.evidenceLine}
                    {e.resolution !== "RESOLVED" ? " · best guess" : ""}
                  </span>
                </li>
              );
            })}
          </ul>
          {items.length < total && (
            <Button size="sm" onClick={more} loading={loading}>
              Show more ({(total - items.length).toLocaleString()})
            </Button>
          )}
        </>
      )}
    </div>
  );
}

function DetailPanel({ snapshotId, sel, onFocus }: { snapshotId: string; sel: Selection; onFocus: (id: string) => void }) {
  const [detail, setDetail] = useState<NodeDetail | null>(null);
  const nodeId = sel?.kind === "node" ? sel.node.id : null;

  useEffect(() => {
    setDetail(null);
    if (!nodeId) return;
    const ctrl = new AbortController();
    getNodeDetail(snapshotId, nodeId, ctrl.signal).then(setDetail).catch(() => undefined);
    return () => ctrl.abort();
  }, [snapshotId, nodeId]);

  if (!sel) {
    return (
      <div className={`${s.panel} ${s.detail}`}>
        <h3>Details</h3>
        <p className={s.detailMeta}>Click a box on the map to see what it is and what connects to it. Double-click to move the map to it.</p>
      </div>
    );
  }
  if (sel.kind === "external") {
    return (
      <div className={`${s.panel} ${s.detail}`}>
        <h3>Outside this repository</h3>
        <p className={s.detailName}>{sel.name}</p>
        <p className={s.detailMeta}>Something from a library, or code CODETRACE could not identify for certain.</p>
      </div>
    );
  }
  if (sel.kind === "edge") {
    const e = sel.edge;
    return (
      <div className={`${s.panel} ${s.detail}`}>
        <h3>Connection</h3>
        <p>
          <code>{shortName(e.srcName)}</code> {EDGE_WORD[e.edgeType] ?? e.edgeType} <code>{shortName(e.dstName)}</code>
        </p>
        <p className={s.detailMeta}>
          Seen in {e.evidencePath}:{e.evidenceLine}
          {e.occurrences > 1 ? ` and ${plural(e.occurrences - 1, "other place")}` : ""}.
        </p>
        {e.resolution !== "RESOLVED" && (
          <Banner tone="yellow" title="Best guess">
            {e.resolution === "AMBIGUOUS"
              ? "The code could refer to more than one thing; this is one of the candidates."
              : "The target could not be identified in this repository."}
          </Banner>
        )}
        <Details summary="Technical details">
          <p className={s.detailMeta}>
            {e.edgeType} · {e.resolution} · confidence {e.confidence.toFixed(2)} · {e.evidenceKind}
            {e.reason ? ` · ${e.reason}` : ""} · {e.resolverVersion}
          </p>
        </Details>
      </div>
    );
  }
  const n = sel.node;
  return (
    <div className={`${s.panel} ${s.detail}`}>
      <h3>{KIND_WORD[n.nodeType].replace(/^./, (c) => c.toUpperCase())}</h3>
      <KindChip kind={nodeKind(n.nodeType, n.path)} name={shortName(n.qualifiedName)} title={n.qualifiedName} />
      <p className={s.detailMeta}>
        {packageOf(n.qualifiedName)}
        <br />
        {n.path ? `${n.path}:${n.startLine}` : ""}
      </p>
      {detail ? (
        <div className={s.stats}>
          <div className={`${s.stat} ${s.statIn}`}>
            <strong>{dependencyCount(detail, "in").toLocaleString()}</strong>
            <span>
              used by
              <Help>How many places in this repository call, use, extend or test it.</Help>
            </span>
          </div>
          <div className={`${s.stat} ${s.statOut}`}>
            <strong>{dependencyCount(detail, "out").toLocaleString()}</strong>
            <span>uses</span>
          </div>
        </div>
      ) : (
        <Skeleton lines={2} />
      )}
      <Connections snapshotId={snapshotId} node={n} onFocus={onFocus} />
      <Button size="sm" icon="map" onClick={() => onFocus(n.id)}>
        Centre the map here
      </Button>
    </div>
  );
}

/** Main code rather than tests, examples or tooling. */
const NOT_MAIN = /(^|\/)[^/]*(test|example|sample)[^/]*\//i;
const isMainCode = (n: GraphNode) => n.path.includes("src/main/") && !NOT_MAIN.test(n.path);

export default function MapView({ snapshot, focus, onFocus }: {
  snapshot: Snapshot;
  focus: string | null;
  onFocus: (id: string) => void;
}) {
  const [kind, setKind] = useState("TYPE");
  const [q, setQ] = useState("");
  const [results, setResults] = useState<GraphNode[]>([]);
  const [start, setStart] = useState<string | null>(null);
  const [depth, setDepth] = useState(1);
  const [limit, setLimit] = useState(FIRST_LIMIT);
  const [types, setTypes] = useState<EdgeType[]>(DEFAULT_TYPES);
  const [guesses, setGuesses] = useState(true);
  const [outside, setOutside] = useState(false);
  const [sub, setSub] = useState<Subgraph | null>(null);
  const [loading, setLoading] = useState(false);
  const [selection, setSelection] = useState<Selection>(null);
  const [error, setError] = useState<string | null>(null);
  const center = focus ?? start;

  // search
  useEffect(() => {
    const ctrl = new AbortController();
    const t = setTimeout(() => {
      searchGraphNodes(snapshot.id, { type: kind || undefined, q: q.trim() || undefined, limit: 50, offset: 0 }, ctrl.signal)
        .then((p) => setResults(p.items))
        .catch(() => undefined);
    }, 200);
    return () => {
      ctrl.abort();
      clearTimeout(t);
    };
  }, [snapshot.id, kind, q]);

  // when nothing is focused, open on the most connected of the first classes
  useEffect(() => {
    if (focus) return;
    const ctrl = new AbortController();
    searchGraphNodes(snapshot.id, { type: "TYPE", limit: 60, offset: 0 }, ctrl.signal)
      .then((p) =>
        Promise.all(
          // prefer main code over tests and examples
          (p.items.some(isMainCode) ? p.items.filter(isMainCode) : p.items).slice(0, 20).map((n) =>
            getNodeDetail(snapshot.id, n.id, ctrl.signal).then((d) => ({ id: n.id, score: dependencyCount(d, "in") + dependencyCount(d, "out") })),
          ),
        ),
      )
      .then((scored) => {
        const best = scored.sort((a, b) => b.score - a.score)[0];
        if (best) setStart(best.id);
      })
      .catch(() => undefined);
    return () => ctrl.abort();
  }, [snapshot.id, focus]);

  const states = useMemo<Resolution[]>(
    () => ["RESOLVED", ...(guesses ? (["AMBIGUOUS"] as const) : []), ...(outside ? (["UNRESOLVED"] as const) : [])],
    [guesses, outside],
  );

  useEffect(() => {
    if (!center) return;
    const ctrl = new AbortController();
    setLoading(true);
    getSubgraph(snapshot.id, { center, depth, types, states, limit }, ctrl.signal)
      .then((g) => {
        setSub(g);
        setError(null);
        const c = g.nodes.find((n) => n.id === center);
        if (c) setSelection({ kind: "node", node: c });
      })
      .catch((e: unknown) => !ctrl.signal.aborted && setError(e instanceof Error ? e.message : "Could not load the map"))
      .finally(() => !ctrl.signal.aborted && setLoading(false));
    return () => ctrl.abort();
  }, [snapshot.id, center, depth, types, states, limit]);

  const go = (id: string) => {
    setLimit(FIRST_LIMIT);
    onFocus(id);
  };

  return (
    <div className={s.mapLayout}>
      <div className={s.panel}>
        <h3>Find</h3>
        <select aria-label="Kind" className={controlClass} value={kind} onChange={(e) => setKind(e.target.value)}>
          <option value="TYPE">Classes</option>
          <option value="METHOD">Methods</option>
          <option value="FIELD">Fields</option>
          <option value="">Everything</option>
        </select>
        <input className={controlClass} type="search" placeholder="Search by name" aria-label="Search" value={q} onChange={(e) => setQ(e.target.value)} />
        <ul className={s.results} aria-label="Results">
          {results.map((n) => (
            <li key={n.id}>
              <button type="button" aria-current={n.id === center} onClick={() => go(n.id)}>
                <span className={s.resultName}>
                  <span className={`${s.kindDot} ${s[`kind${nodeKind(n.nodeType, n.path)}`]}`} aria-hidden />
                  {shortName(n.qualifiedName)}
                </span>
                <span className={s.resultPkg}>{packageOf(n.qualifiedName)}</span>
              </button>
            </li>
          ))}
          {results.length === 0 && <li className={s.detailMeta}>No matches.</li>}
        </ul>
      </div>

      <div className={s.center}>
        {error && <Banner tone="red">{error}</Banner>}
        {sub ? (
          <>
            <GraphCanvas subgraph={sub} selectedId={selection?.kind === "node" ? selection.node.id : null} onSelect={(sel, recenter) => (recenter ? go(recenter) : setSelection(sel))} />
            <div className={s.legend}>
              {(["TYPE", "METHOD", "FIELD", "TEST"] as const).map((k) => (
                <span key={k}>
                  <span className={`${s.kindSwatch} ${s[`kind${k}`]}`} /> {k === "TEST" ? "test" : KIND_WORD[k]}
                </span>
              ))}
              <span>
                <span className={s.swatch} /> certain link
              </span>
              <span>
                <span className={`${s.swatch} ${s.swatchGuess}`} /> best guess
              </span>
              <span>
                <span className={s.swatchOutside} /> outside the repository
              </span>
              {loading && (
                <span>
                  <Spinner size={12} /> Updating…
                </span>
              )}
              {sub.truncated && (
                <span>
                  Showing {plural(sub.edges.length, "link")}.{" "}
                  <Button size="sm" variant="invisible" onClick={() => setLimit((l) => Math.min(500, l + 60))} disabled={limit >= 500}>
                    Show more
                  </Button>
                </span>
              )}
            </div>
            <Details summary="Filters">
              <div className={s.filters}>
                <label>
                  Steps out
                  <select className={controlClass} style={{ width: 70, height: 28 }} value={depth} onChange={(e) => setDepth(Number(e.target.value))}>
                    {[1, 2, 3, 4].map((d) => (
                      <option key={d} value={d}>
                        {d}
                      </option>
                    ))}
                  </select>
                </label>
                {LINK_TYPES.map((t) => (
                  <label key={t.type}>
                    <input
                      type="checkbox"
                      checked={types.includes(t.type)}
                      onChange={() => {
                        setLimit(FIRST_LIMIT);
                        setTypes((cur) => (cur.includes(t.type) ? cur.filter((x) => x !== t.type) : [...cur, t.type]));
                      }}
                    />
                    {t.label}
                  </label>
                ))}
                <label>
                  <input type="checkbox" checked={guesses} onChange={(e) => setGuesses(e.target.checked)} /> best guesses
                </label>
                <label>
                  <input type="checkbox" checked={outside} onChange={(e) => setOutside(e.target.checked)} /> things outside the repository
                </label>
              </div>
            </Details>
          </>
        ) : (
          <div className={s.canvas} style={{ display: "grid", placeItems: "center" }}>
            {center ? <Spinner size={24} label="Loading the map" /> : <p className={s.detailMeta}>Pick something on the left to start.</p>}
          </div>
        )}
      </div>

      <DetailPanel snapshotId={snapshot.id} sel={selection} onFocus={go} />
    </div>
  );
}
