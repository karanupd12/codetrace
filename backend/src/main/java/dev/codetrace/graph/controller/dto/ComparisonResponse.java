package dev.codetrace.graph.controller.dto;

import dev.codetrace.graph.model.ChangeCount;
import dev.codetrace.graph.model.SnapshotComparison;
import java.util.List;

/** What changed between two published snapshots of one repository, as counts per change kind and type. */
public record ComparisonResponse(SnapshotResponse base, SnapshotResponse head, List<ChangeCount> nodes,
                                 List<ChangeCount> edges) {
    public static ComparisonResponse from(SnapshotComparison c) {
        return new ComparisonResponse(SnapshotResponse.from(c.base()), SnapshotResponse.from(c.head()), c.nodes(),
                c.edges());
    }
}
