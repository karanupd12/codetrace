"use client";

import { useState } from "react";
import { findPaths, type GraphNode, type PathResult, type Snapshot } from "@/lib/api";
import { EDGE_WORD, fileName, kindOfName, plural, shortName } from "@/lib/words";
import Icon from "@/components/Icon";
import { Badge, Banner, Button, EmptyState } from "@/components/ui";
import { KindChip } from "@/components/viz";
import NodePicker from "./NodePicker";
import s from "./explore.module.css";

const TYPES = "CALLS,REFERENCES,EXTENDS,IMPLEMENTS,OVERRIDES,TESTS,CONTAINS";

type Answer = { result: PathResult; reversed: boolean };

/** "How is A connected to B?": the shortest chains of dependencies between two parts of the code. */
export default function PathView({ snapshot, onShow }: { snapshot: Snapshot; onShow: (id: string) => void }) {
  const [from, setFrom] = useState<GraphNode | null>(null);
  const [to, setTo] = useState<GraphNode | null>(null);
  const [answer, setAnswer] = useState<Answer | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function find() {
    if (!from || !to) return;
    setBusy(true);
    setError(null);
    setAnswer(null);
    try {
      const forward = await findPaths(snapshot.id, { from: from.id, to: to.id, maxDepth: 8, direction: "OUT", types: TYPES });
      if (forward.found) {
        setAnswer({ result: forward, reversed: false });
      } else {
        const back = await findPaths(snapshot.id, { from: to.id, to: from.id, maxDepth: 8, direction: "OUT", types: TYPES });
        setAnswer({ result: back.found ? back : forward, reversed: back.found });
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not search.");
    } finally {
      setBusy(false);
    }
  }

  const a = answer?.reversed ? to : from;
  const b = answer?.reversed ? from : to;

  return (
    <div style={{ display: "grid", gridTemplateColumns: "minmax(0, 1fr)", gap: 16 }}>
      <div className={s.pathForm}>
        <NodePicker snapshotId={snapshot.id} label="From" value={from} onChange={setFrom} />
        <span style={{ paddingBottom: 8, color: "var(--muted)" }}>
          <Icon name="arrowRight" />
        </span>
        <NodePicker snapshotId={snapshot.id} label="To" value={to} onChange={setTo} />
        <Button variant="primary" icon="search" onClick={find} loading={busy} disabled={!from || !to || from.id === to.id}>
          Find the connection
        </Button>
      </div>
      {error && <Banner tone="red">{error}</Banner>}
      {!answer && !busy && (
        <EmptyState icon="route" title="Pick two parts of the code">
          For example a controller and a database class. CODETRACE shows the shortest chains of calls and uses that connect
          them, with the file and line of each step.
        </EmptyState>
      )}
      {answer && !answer.result.found && (
        <Banner tone="yellow" title="No connection found">
          Neither depends on the other within 8 steps{answer.result.truncated ? " (the search stopped at its size limit)" : ""}.
        </Banner>
      )}
      {answer?.result.found && a && b && (
        <>
          <p>
            <strong>{shortName(a.qualifiedName)}</strong> depends on <strong>{shortName(b.qualifiedName)}</strong> in{" "}
            {plural(answer.result.shortestLength ?? 0, "step")}
            {answer.reversed ? " (it is this way round)" : ""}. {answer.result.paths.length > 1 ? `${answer.result.paths.length} shortest routes:` : ""}
          </p>
          <ol className={s.pathList}>
            {answer.result.paths.map((p, i) => (
              <li key={i} className={s.path}>
                <div className={s.pathHead}>
                  <span>Route {i + 1}</span>
                  {p.certain ? <Badge tone="green">certain</Badge> : <Badge tone="yellow">includes a best guess</Badge>}
                </div>
                <ol className={s.flow}>
                  {p.edges.map((e, j) => (
                    <li key={e.id} className={s.flowStep}>
                      {j === 0 && <KindChip kind={kindOfName(e.srcName, e.evidencePath)} name={shortName(e.srcName)} title={e.srcName} />}
                      <span className={`${s.flowArrow} ${e.resolution !== "RESOLVED" ? s.flowGuess : ""}`} title={`${e.evidencePath}:${e.evidenceLine}`}>
                        <span className={s.flowVerb}>{EDGE_WORD[e.edgeType] ?? e.edgeType.toLowerCase()}</span>
                        <span className={s.flowLine} aria-hidden />
                        <span className={s.flowWhere}>
                          {fileName(e.evidencePath)}:{e.evidenceLine}
                          {e.resolution !== "RESOLVED" ? " · guess" : ""}
                        </span>
                      </span>
                      <KindChip kind={kindOfName(e.dstName, p.edges[j + 1]?.evidencePath)} name={shortName(e.dstName)} title={e.dstName} />
                    </li>
                  ))}
                </ol>
                <div>
                  <Button size="sm" icon="map" onClick={() => onShow(answer.reversed ? (to as GraphNode).id : (from as GraphNode).id)}>
                    Show on the map
                  </Button>
                </div>
              </li>
            ))}
          </ol>
        </>
      )}
    </div>
  );
}
