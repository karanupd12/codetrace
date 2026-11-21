package dev.codetrace.graph.model;

/** How many edges of one type and resolution state touch a node. */
public record Degree(String edgeType, String resolution, long count) {
}
