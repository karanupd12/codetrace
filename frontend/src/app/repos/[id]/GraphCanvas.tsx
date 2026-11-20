"use client";

import "@xyflow/react/dist/style.css";
import dagre from "@dagrejs/dagre";
import { Background, Controls, MarkerType, ReactFlow, type Edge, type Node } from "@xyflow/react";
import { useMemo } from "react";
import type { GraphEdge, GraphNode, NodeType, Subgraph } from "@/lib/api";
import styles from "./graph.module.css";

export type Selection =
  | { kind: "node"; node: GraphNode }
  | { kind: "external"; name: string }
  | { kind: "edge"; edge: GraphEdge }
  | null;

const NODE_W = 240;
const NODE_H = 56;
const EXTERNAL = "ext:";
const MAX_PER_COLUMN = 10;
const ROW_GAP = 22;
const COLUMN_GAP = 40;
const RANK_GAP = 140;

const NODE_COLOR: Record<NodeType, string> = {
  TYPE: "#3b82f6",
  METHOD: "#22a559",
  FIELD: "#d97706",
  FILE: "#6b7280",
  PACKAGE: "#8b5cf6",
};

const EDGE_STYLE = {
  RESOLVED: { stroke: "#8a93a3", dash: undefined },
  AMBIGUOUS: { stroke: "#c98a00", dash: "7 4" },
  UNRESOLVED: { stroke: "#9aa3b0", dash: "2 5" },
} as const;

function title(n: GraphNode) {
  if (n.nodeType === "METHOD") return n.signature || n.simpleName;
  return n.simpleName;
}

function edgeLabel(e: GraphEdge) {
  const base = e.edgeType.toLowerCase();
  if (e.resolution === "RESOLVED") return e.confidence < 1 ? `${base} · ${e.confidence.toFixed(2)}` : base;
  return `${base} · ${e.resolution.toLowerCase()} ${e.confidence.toFixed(2)}`;
}

/** Converts the API subgraph to React Flow nodes/edges and lays them out left-to-right. */
export function buildFlow(sub: Subgraph): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = sub.nodes.map((n) => ({
    id: n.id,
    position: { x: 0, y: 0 },
    data: {
      label: (
        <div className={styles.nodeLabel}>
          <strong title={n.qualifiedName}>{title(n)}</strong>
          <span>{n.subtype || n.nodeType.toLowerCase()}</span>
        </div>
      ),
      selection: { kind: "node", node: n } satisfies Selection,
    },
    style: {
      width: NODE_W,
      height: NODE_H,
      borderLeft: `5px solid ${NODE_COLOR[n.nodeType]}`,
      outline: n.id === sub.center ? "2px solid #3b82f6" : undefined,
    },
  }));

  const ghosts = new Map<string, string>(); // dstName -> ghost node id
  const edges: Edge[] = [];
  for (const e of sub.edges) {
    let target = e.dstId;
    if (!target) {
      const id = EXTERNAL + e.dstName;
      if (!ghosts.has(id)) {
        ghosts.set(id, e.dstName);
        nodes.push({
          id,
          position: { x: 0, y: 0 },
          data: {
            label: (
              <div className={styles.nodeLabel}>
                <strong title={e.dstName}>{e.dstName}</strong>
                <span>not in repository</span>
              </div>
            ),
            selection: { kind: "external", name: e.dstName } satisfies Selection,
          },
          style: { width: NODE_W, height: NODE_H, borderStyle: "dashed", opacity: 0.8 },
        });
      }
      target = id;
    }
    const style = EDGE_STYLE[e.resolution];
    edges.push({
      id: `e${e.id}`,
      source: e.srcId,
      target,
      label: edgeLabel(e),
      markerEnd: { type: MarkerType.ArrowClosed, color: style.stroke },
      style: { stroke: style.stroke, strokeDasharray: style.dash, strokeWidth: 1.5 },
      labelStyle: { fontSize: 10 },
      data: { selection: { kind: "edge", edge: e } satisfies Selection },
    });
  }

  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: "LR", nodesep: 28, ranksep: 120 });
  nodes.forEach((n) => g.setNode(n.id, { width: NODE_W, height: NODE_H }));
  edges.forEach((e) => g.setEdge(e.source, e.target));
  dagre.layout(g);

  // Dagre puts every neighbour of a hub in one tall column. Wrap crowded ranks into several columns so the
  // whole graph fits on screen at a readable zoom.
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

export default function GraphCanvas({
  subgraph,
  onSelect,
}: {
  subgraph: Subgraph;
  onSelect: (s: Selection, recenterId?: string) => void;
}) {
  const flow = useMemo(() => buildFlow(subgraph), [subgraph]);

  return (
    <div className={styles.canvas}>
      <ReactFlow
        key={`${subgraph.center}:${subgraph.depth}:${flow.edges.length}`}
        nodes={flow.nodes}
        edges={flow.edges}
        fitView
        minZoom={0.1}
        nodesConnectable={false}
        onNodeClick={(_, n) => onSelect(n.data.selection as Selection)}
        onNodeDoubleClick={(_, n) => {
          if (!n.id.startsWith(EXTERNAL)) onSelect(n.data.selection as Selection, n.id);
        }}
        onEdgeClick={(_, e) => onSelect((e.data as { selection: Selection }).selection)}
        onPaneClick={() => onSelect(null)}
      >
        <Background />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  );
}
