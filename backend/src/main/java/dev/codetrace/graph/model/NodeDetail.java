package dev.codetrace.graph.model;

import java.util.List;

/** A node with how many edges leave and enter it, per edge type and resolution state. */
public record NodeDetail(NodeView node, List<Degree> outgoing, List<Degree> incoming) {
}
