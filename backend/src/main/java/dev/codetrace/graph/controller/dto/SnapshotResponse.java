package dev.codetrace.graph.controller.dto;

import dev.codetrace.graph.model.Snapshot;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SnapshotResponse(UUID id, UUID repositoryId, String commitHash, String parserVersion,
                               String builderVersion, String status, Integer nodeCount, Integer edgeCount,
                               Integer resolvedEdgeCount, Integer ambiguousEdgeCount, Integer unresolvedEdgeCount,
                               String failureReason, OffsetDateTime createdAt, OffsetDateTime publishedAt) {
    public static SnapshotResponse from(Snapshot s) {
        return new SnapshotResponse(s.id(), s.repositoryId(), s.commitHash(), s.parserVersion(), s.builderVersion(),
                s.status(), s.nodeCount(), s.edgeCount(), s.resolvedEdgeCount(), s.ambiguousEdgeCount(),
                s.unresolvedEdgeCount(), s.failureReason(), s.createdAt(), s.publishedAt());
    }
}
