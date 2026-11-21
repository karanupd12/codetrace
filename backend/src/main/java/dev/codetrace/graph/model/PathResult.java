package dev.codetrace.graph.model;

import java.util.List;
import java.util.UUID;

/** Every shortest path between two nodes, up to a limit; {@code truncated} says a search bound was hit. */
public record PathResult(UUID from, UUID to, boolean found, Integer shortestLength, List<GraphPath> paths,
                         boolean truncated, int maxDepth) {
}
