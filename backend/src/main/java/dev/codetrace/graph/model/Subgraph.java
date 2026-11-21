package dev.codetrace.graph.model;

import java.util.List;
import java.util.UUID;

/** A bounded neighbourhood around {@code center}; {@code truncated} says a limit cut it short. */
public record Subgraph(UUID center, int depth, List<NodeView> nodes, List<EdgeView> edges, boolean truncated) {
}
