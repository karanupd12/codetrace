"use client";

import { useEffect, useState } from "react";
import {
  compareSnapshots,
  listEdgeChanges,
  listNodeChanges,
  type EdgeChange,
  type NodeChange,
  type Snapshot,
  type SnapshotComparison,
} from "@/lib/api";
import { EDGE_WORD, KIND_WORD, fileName, shortName } from "@/lib/words";
import { Badge, Banner, Card, Field, ShowMore, Skeleton, controlClass, type Tone } from "@/components/ui";
import s from "./explore.module.css";

const PAGE = 25;
const TONE: Record<string, Tone> = { ADDED: "green", REMOVED: "red", CHANGED: "orange" };
const WORD: Record<string, string> = { ADDED: "Added", REMOVED: "Removed", CHANGED: "Changed" };
const LINK_TYPES = "CALLS,REFERENCES,EXTENDS,IMPLEMENTS,OVERRIDES,TESTS";

function count(c: SnapshotComparison, list: "nodes" | "edges", change: string) {
  return c[list].filter((x) => x.change === change).reduce((n, x) => n + x.count, 0);
}

function NodeList({ base, head, total }: { base: string; head: string; total: number }) {
  const [filter, setFilter] = useState("");
  const [items, setItems] = useState<NodeChange[]>([]);
  const [shown, setShown] = useState<number>(total);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    const ctrl = new AbortController();
    listNodeChanges(base, head, { change: filter || undefined, limit: PAGE }, ctrl.signal)
      .then((p) => {
        setItems(p.items);
        setShown(p.total);
      })
      .catch(() => undefined);
    return () => ctrl.abort();
  }, [base, head, filter]);
  const more = () => {
    setLoading(true);
    listNodeChanges(base, head, { change: filter || undefined, limit: PAGE, offset: items.length })
      .then((p) => setItems((cur) => [...cur, ...p.items]))
      .finally(() => setLoading(false));
  };
  return (
    <Card
      title="Code that was added, removed or changed"
      padded
      actions={
        <select aria-label="Show" className={controlClass} style={{ width: "auto" }} value={filter} onChange={(e) => setFilter(e.target.value)}>
          <option value="">All</option>
          <option value="ADDED">Added</option>
          <option value="REMOVED">Removed</option>
          <option value="CHANGED">Changed</option>
        </select>
      }
    >
      <ul className={s.changeList}>
        {items.map((n) => (
          <li key={`${n.change}|${n.nodeType}|${n.path}|${n.qualifiedName}|${n.signature}`}>
            <Badge tone={TONE[n.change]}>{WORD[n.change]}</Badge>
            <span>
              <code>{shortName(n.qualifiedName)}</code> <span className={s.connWhere}>· {KIND_WORD[n.nodeType as keyof typeof KIND_WORD] ?? n.nodeType} · {fileName(n.path)}</span>
              {n.change === "CHANGED" && n.baseModifiers !== n.headModifiers && (
                <span className={s.connWhere}>
                  {" "}
                  · now “{n.headModifiers || "package-private"}” (was “{n.baseModifiers || "package-private"}”)
                </span>
              )}
            </span>
          </li>
        ))}
      </ul>
      <ShowMore shown={items.length} total={shown} loading={loading} onMore={more} />
    </Card>
  );
}

function EdgeList({ base, head }: { base: string; head: string }) {
  const [filter, setFilter] = useState("");
  const [items, setItems] = useState<EdgeChange[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    const ctrl = new AbortController();
    listEdgeChanges(base, head, { change: filter || undefined, types: LINK_TYPES, limit: PAGE }, ctrl.signal)
      .then((p) => {
        setItems(p.items);
        setTotal(p.total);
      })
      .catch(() => undefined);
    return () => ctrl.abort();
  }, [base, head, filter]);
  const more = () => {
    setLoading(true);
    listEdgeChanges(base, head, { change: filter || undefined, types: LINK_TYPES, limit: PAGE, offset: items.length })
      .then((p) => setItems((cur) => [...cur, ...p.items]))
      .finally(() => setLoading(false));
  };
  return (
    <Card
      title="Connections that appeared or disappeared"
      actions={
        <select aria-label="Show" className={controlClass} style={{ width: "auto" }} value={filter} onChange={(e) => setFilter(e.target.value)}>
          <option value="">All</option>
          <option value="ADDED">Appeared</option>
          <option value="REMOVED">Disappeared</option>
        </select>
      }
    >
      {total === 0 ? (
        <p className={s.connWhere}>No calls or uses changed.</p>
      ) : (
        <>
          <ul className={s.changeList}>
            {items.map((e, i) => (
              <li key={`${i}|${e.change}|${e.srcName}|${e.edgeType}|${e.dstName}|${e.resolutionState}`}>
                <Badge tone={TONE[e.change]}>{e.change === "ADDED" ? "New" : "Gone"}</Badge>
                <span>
                  <code>{shortName(e.srcName)}</code> {EDGE_WORD[e.edgeType as keyof typeof EDGE_WORD] ?? e.edgeType.toLowerCase()}{" "}
                  <code>{e.dstType ? shortName(e.dstName) : e.dstName}</code>{" "}
                  <span className={s.connWhere}>
                    · {fileName(e.evidencePath)}:{e.evidenceLine}
                    {e.resolutionState !== "RESOLVED" ? " · best guess" : ""}
                  </span>
                </span>
              </li>
            ))}
          </ul>
          <ShowMore shown={items.length} total={total} loading={loading} onMore={more} />
        </>
      )}
    </Card>
  );
}

/** Two prepared versions side by side: what code and which connections appeared or disappeared. */
export default function CompareView({ ready, label }: { ready: Snapshot[]; label: (s: Snapshot) => string }) {
  const [head, setHead] = useState(ready[0]?.id ?? "");
  const [base, setBase] = useState(ready[1]?.id ?? "");
  const [result, setResult] = useState<SnapshotComparison | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!base || !head || base === head) {
      setResult(null);
      return;
    }
    const ctrl = new AbortController();
    setResult(null);
    setError(null);
    compareSnapshots(base, head, ctrl.signal)
      .then(setResult)
      .catch((e: unknown) => !ctrl.signal.aborted && setError(e instanceof Error ? e.message : "Could not compare"));
    return () => ctrl.abort();
  }, [base, head]);

  if (ready.length < 2) {
    return <Banner title="Two prepared versions are needed">Prepare another version on the Overview tab to compare them.</Banner>;
  }

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className={s.pathForm} style={{ gridTemplateColumns: "minmax(0,1fr) minmax(0,1fr)" }}>
        <Field label="Older version" htmlFor="cmp-base">
          <select id="cmp-base" className={controlClass} value={base} onChange={(e) => setBase(e.target.value)}>
            {ready.map((sn) => (
              <option key={sn.id} value={sn.id}>
                {label(sn)}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Newer version" htmlFor="cmp-head">
          <select id="cmp-head" className={controlClass} value={head} onChange={(e) => setHead(e.target.value)}>
            {ready.map((sn) => (
              <option key={sn.id} value={sn.id}>
                {label(sn)}
              </option>
            ))}
          </select>
        </Field>
      </div>
      {base === head && <Banner tone="yellow">Pick two different versions.</Banner>}
      {error && <Banner tone="red">{error}</Banner>}
      {base !== head && !result && !error && <Skeleton lines={3} />}
      {result && (
        <>
          <div className={s.compareSummary}>
            <div className={s.stat}>
              <strong>{count(result, "nodes", "ADDED").toLocaleString()}</strong>
              <span>classes, methods and fields added</span>
            </div>
            <div className={s.stat}>
              <strong>{count(result, "nodes", "REMOVED").toLocaleString()}</strong>
              <span>removed</span>
            </div>
            <div className={s.stat}>
              <strong>{count(result, "nodes", "CHANGED").toLocaleString()}</strong>
              <span>changed (visibility or kind)</span>
            </div>
            <div className={s.stat}>
              <strong>
                +{count(result, "edges", "ADDED").toLocaleString()} / −{count(result, "edges", "REMOVED").toLocaleString()}
              </strong>
              <span>connections</span>
            </div>
          </div>
          {result.nodes.length === 0 && result.edges.length === 0 ? (
            <Banner tone="green" title="No structural difference">
              The two versions declare the same code with the same connections.
            </Banner>
          ) : (
            <>
              <NodeList base={base} head={head} total={result.nodes.reduce((n, x) => n + x.count, 0)} />
              <EdgeList base={base} head={head} />
            </>
          )}
        </>
      )}
    </div>
  );
}
