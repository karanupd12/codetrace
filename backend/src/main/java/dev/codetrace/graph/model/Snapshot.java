package dev.codetrace.graph.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A graph snapshot of one (repository, commit, parser version, builder version); readable only once published. */
public record Snapshot(UUID id, UUID repositoryId, String commitHash, String parserVersion, String builderVersion,
                       UUID sourceJobId, String status, Integer nodeCount, Integer edgeCount, Integer resolvedEdgeCount,
                       Integer ambiguousEdgeCount, Integer unresolvedEdgeCount, String failureReason,
                       OffsetDateTime createdAt, OffsetDateTime publishedAt) {
    public boolean published() {
        return "PUBLISHED".equals(status);
    }
}
