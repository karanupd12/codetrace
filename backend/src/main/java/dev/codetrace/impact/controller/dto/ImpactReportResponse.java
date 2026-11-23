package dev.codetrace.impact.controller.dto;

import dev.codetrace.impact.model.ImpactReport;
import dev.codetrace.impact.risk.RiskSignal.RiskFactor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A report header with its decoded risk factors; {@code jobId} is the job that computes it (for its progress). */
public record ImpactReportResponse(UUID id, UUID repositoryId, String baseCommit, String headCommit, UUID baseSnapshotId,
                                   UUID headSnapshotId, String algorithmVersion, int depth, UUID jobId, String status,
                                   Integer riskScore, String riskLevel, List<RiskFactor> riskFactors,
                                   Integer changedCount, Integer affectedCount, Integer testsAffectedCount,
                                   Integer possibleCount, Integer unmappedHunks, Integer nonJavaFiles, Boolean truncated,
                                   String failureReason, OffsetDateTime createdAt, OffsetDateTime completedAt,
                                   List<String> notes) {
    public static ImpactReportResponse from(ImpactReport r, List<RiskFactor> riskFactors, List<String> notes) {
        return new ImpactReportResponse(r.id(), r.repositoryId(), r.baseCommit(), r.headCommit(), r.baseSnapshotId(),
                r.headSnapshotId(), r.algorithmVersion(), r.depth(), r.jobId(), r.status(), r.riskScore(), r.riskLevel(), riskFactors,
                r.changedCount(), r.affectedCount(), r.testsAffectedCount(), r.possibleCount(), r.unmappedHunks(),
                r.nonJavaFiles(), r.truncated(), r.failureReason(), r.createdAt(), r.completedAt(), notes);
    }
}
