package dev.codetrace.graph.model;

/** How many nodes or edges of one type changed in one way between two snapshots. */
public record ChangeCount(String change, String kind, long count) {
}
