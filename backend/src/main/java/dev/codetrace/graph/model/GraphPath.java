package dev.codetrace.graph.model;

import java.util.List;

/** One shortest path. {@code certain} is true only if every hop is RESOLVED. */
public record GraphPath(int length, double minConfidence, boolean certain, List<EdgeView> edges) {
}
