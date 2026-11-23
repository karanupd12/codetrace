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
import { EDGE_WORD, KIND_WORD, fileName, kindOfName, nodeKind, shortName } from "@/lib/words";
import { Banner, Card, Field, ShowMore, Skeleton, controlClass } from "@/components/ui";
import { KindChip, SplitBar, StatRow, StatTile } from "@/components/viz";
import s from "./explore.module.css";

const PAGE = 25;
const GUTTER: Record<string, { sign: string; cls: string; word: string }> = {
  ADDED: { sign: "+", cls: s.gutterAdded, word: "Added" },
  REMOVED: { sign: "−", cls: s.gutterRemoved, word: "Removed" },
  CHANGED: { sign: "~", cls: s.gutterChanged, word: "Changed" },
};

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
          <li key={`${n.change}|${n.nodeType}|${n.path}|${n.qualifiedName}|${n.signature}`} className={GUTTER[n.change].cls}>
            <span className={s.gutterSign} title={GUTTER[n.change].word} aria-label={GUTTER[n.change].word}>
              {GUTTER[n.change].sign}
            </span>
            <span className={s.changeBody}>
              <KindChip kind={nodeKind(n.nodeType, n.path)} name={shortName(n.qualifiedName)} title={n.qualifiedName} />{" "}
              <span className={s.connWhere}>
                {KIND_WORD[n.nodeType as keyof typeof KIND_WORD] ?? n.nodeType} · {fileName(n.path)}
              </span>
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
              <li key={`${i}|${e.change}|${e.srcName}|${e.edgeType}|${e.dstName}|${e.resolutionState}`} className={GUTTER[e.change].cls}>
                <span className={s.gutterSign} title={e.change === "ADDED" ? "New" : "Gone"} aria-label={e.change === "ADDED" ? "New" : "Gone"}>
                  {GUTTER[e.change].sign}
                </span>
                <span className={s.changeBody}>
                  <KindChip kind={kindOfName(e.srcName, e.evidencePath)} name={shortName(e.srcName)} title={e.srcName} />{" "}
                  <span className={s.changeVerb}>{EDGE_WORD[e.edgeType as keyof typeof EDGE_WORD] ?? e.edgeType.toLowerCase()}</span>{" "}
                  {e.dstType ? <KindChip kind={kindOfName(e.dstName)} name={shortName(e.dstName)} title={e.dstName} /> : <code>{e.dstName}</code>}{" "}
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
    <div style={{ display: "grid", gridTemplateColumns: "minmax(0, 1fr)", gap: 16 }}>
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
          <StatRow>
            <StatTile icon="plus" tone="green" value={count(result, "nodes", "ADDED")} label="classes, methods and fields added" />
            <StatTile icon="x" tone="red" value={count(result, "nodes", "REMOVED")} label="removed" />
            <StatTile icon="diff" tone="orange" value={count(result, "nodes", "CHANGED")} label="changed (visibility or kind)" />
            <StatTile
              icon="link"
              tone="teal"
              value={count(result, "edges", "ADDED") + count(result, "edges", "REMOVED")}
              display={`+${count(result, "edges", "ADDED").toLocaleString()} / −${count(result, "edges", "REMOVED").toLocaleString()}`}
              label="connections"
            />
          </StatRow>
          {(result.nodes.length > 0 || result.edges.length > 0) && (
            <Card>
              <div className={s.diffBars}>
                <div>
                  <h3 className={s.diffTitle}>Code</h3>
                  <SplitBar
                    label="Code changes"
                    parts={[
                      { key: "a", label: "Added", value: count(result, "nodes", "ADDED"), tone: "green" },
                      { key: "c", label: "Changed", value: count(result, "nodes", "CHANGED"), tone: "orange" },
                      { key: "r", label: "Removed", value: count(result, "nodes", "REMOVED"), tone: "red" },
                    ]}
                  />
                </div>
                <div>
                  <h3 className={s.diffTitle}>Connections</h3>
                  <SplitBar
                    label="Connection changes"
                    parts={[
                      { key: "a", label: "Appeared", value: count(result, "edges", "ADDED"), tone: "green" },
                      { key: "r", label: "Disappeared", value: count(result, "edges", "REMOVED"), tone: "red" },
                    ]}
                  />
                </div>
              </div>
            </Card>
          )}
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
