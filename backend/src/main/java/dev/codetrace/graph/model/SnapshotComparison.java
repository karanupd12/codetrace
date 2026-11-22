package dev.codetrace.graph.model;

import java.util.List;

/** Summary of what changed in the dependency graph from a base snapshot to a head snapshot. */
public record SnapshotComparison(Snapshot base, Snapshot head, List<ChangeCount> nodes, List<ChangeCount> edges) {
}
