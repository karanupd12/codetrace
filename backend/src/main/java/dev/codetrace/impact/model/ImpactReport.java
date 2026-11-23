package dev.codetrace.impact.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** An impact report header as stored; its findings are stored separately. Immutable once COMPLETED. */
public record ImpactReport(UUID id, UUID repositoryId, String baseCommit, String headCommit, UUID baseSnapshotId,
                           UUID headSnapshotId, String algorithmVersion, int depth, UUID jobId, String status,
                           Integer riskScore, String riskLevel, String riskFactorsJson, Integer changedCount,
                           Integer affectedCount, Integer testsAffectedCount, Integer possibleCount,
                           Integer unmappedHunks, Integer nonJavaFiles, Boolean truncated, String failureReason,
                           OffsetDateTime createdAt, OffsetDateTime completedAt, String notesJson) {
    public boolean completed() {
        return "COMPLETED".equals(status);
    }
}
