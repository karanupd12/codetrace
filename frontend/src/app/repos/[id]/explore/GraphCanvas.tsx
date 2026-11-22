"use client";

import "@xyflow/react/dist/style.css";
import dagre from "@dagrejs/dagre";
import { Background, Controls, MarkerType, ReactFlow, type Edge, type Node } from "@xyflow/react";
import { useMemo } from "react";
import type { GraphEdge, GraphNode, Subgraph } from "@/lib/api";
import { EDGE_WORD, KIND_WORD } from "@/lib/words";
import s from "./explore.module.css";

export type Selection =
  | { kind: "node"; node: GraphNode }
  | { kind: "external"; name: string }
  | { kind: "edge"; edge: GraphEdge }
  | null;

const NODE_W = 230;
const NODE_H = 54;
const EXTERNAL = "ext:";
const MAX_PER_COLUMN = 10;
const ROW_GAP = 22;
const COLUMN_GAP = 40;
const RANK_GAP = 140;

function title(n: GraphNode) {
  return n.nodeType === "METHOD" ? n.signature || n.simpleName : n.simpleName;
}

/** Converts the API subgraph to React Flow nodes and edges, laid out left to right. */
function buildFlow(sub: Subgraph, selectedId: string | null): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = sub.nodes.map((n) => ({
    id: n.id,
    position: { x: 0, y: 0 },
    className: [s.node, s[`kind${n.nodeType}`], n.id === sub.center ? s.centerNode : "", n.id === selectedId ? s.selected : ""].join(" "),
    data: {
      label: (
        <div className={s.nodeLabel}>
          <strong title={n.qualifiedName}>{title(n)}</strong>
          <span>{KIND_WORD[n.nodeType]}</span>
        </div>
      ),
      selection: { kind: "node", node: n } satisfies Selection,
    },
    style: { width: NODE_W, height: NODE_H },
  }));

  const ghosts = new Set<string>();
  const edges: Edge[] = [];
  for (const e of sub.edges) {
    let target = e.dstId;
    if (!target) {
      const id = EXTERNAL + e.dstName;
      if (!ghosts.has(id)) {
        ghosts.add(id);
        nodes.push({
          id,
          position: { x: 0, y: 0 },
          className: `${s.node} ${s.external}`,
          data: {
            label: (
              <div className={s.nodeLabel}>
                <strong title={e.dstName}>{e.dstName}</strong>
                <span>outside this repository</span>
              </div>
            ),
            selection: { kind: "external", name: e.dstName } satisfies Selection,
          },
          style: { width: NODE_W, height: NODE_H },
        });
      }
      target = id;
    }
    const guess = e.resolution !== "RESOLVED";
    edges.push({
      id: `e${e.id}`,
      source: e.srcId,
      target,
      label: EDGE_WORD[e.edgeType] ?? e.edgeType.toLowerCase(),
      className: guess ? s.edgeGuess : s.edgeSure,
      markerEnd: { type: MarkerType.ArrowClosed },
      labelBgPadding: [4, 2],
      data: { selection: { kind: "edge", edge: e } satisfies Selection },
    });
  }

  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: "LR", nodesep: 28, ranksep: 120 });
  nodes.forEach((n) => g.setNode(n.id, { width: NODE_W, height: NODE_H }));
  edges.forEach((e) => g.setEdge(e.source, e.target));
  dagre.layout(g);

  // Dagre puts every neighbour of a busy node in one tall column; wrap crowded ranks into several columns.
  const ranks = new Map<number, Node[]>();
  for (const n of nodes) {
    const x = Math.round(g.node(n.id).x);
    ranks.set(x, [...(ranks.get(x) ?? []), n]);
  }
  let cursorX = 0;
  for (const x of [...ranks.keys()].sort((a, b) => a - b)) {
    const group = ranks.get(x)!.sort((a, b) => g.node(a.id).y - g.node(b.id).y);
    const cols = Math.ceil(group.length / MAX_PER_COLUMN);
    const rows = Math.ceil(group.length / cols);
    group.forEach((n, i) => {
      const col = Math.floor(i / rows);
      const row = i % rows;
      n.position = {
        x: cursorX + col * (NODE_W + COLUMN_GAP),
        y: row * (NODE_H + ROW_GAP) - (rows * (NODE_H + ROW_GAP)) / 2,
      };
    });
    cursorX += cols * (NODE_W + COLUMN_GAP) + RANK_GAP;
  }
  return { nodes, edges };
}

export default function GraphCanvas({ subgraph, selectedId, onSelect }: {
  subgraph: Subgraph;
  selectedId: string | null;
  onSelect: (s: Selection, recenterId?: string) => void;
}) {
  const flow = useMemo(() => buildFlow(subgraph, selectedId), [subgraph, selectedId]);
  return (
    <div className={s.canvas}>
      <ReactFlow
        key={`${subgraph.center}:${subgraph.depth}:${flow.edges.length}`}
        nodes={flow.nodes}
        edges={flow.edges}
        fitView
        minZoom={0.1}
        nodesConnectable={false}
        proOptions={{ hideAttribution: true }}
        onNodeClick={(_, n) => onSelect(n.data.selection as Selection)}
        onNodeDoubleClick={(_, n) => {
          if (!n.id.startsWith(EXTERNAL)) onSelect(n.data.selection as Selection, n.id);
        }}
        onEdgeClick={(_, e) => onSelect((e.data as { selection: Selection }).selection)}
        onPaneClick={() => onSelect(null)}
      >
        <Background gap={20} size={1} />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  );
}
