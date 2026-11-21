package dev.codetrace.impact.controller;

import dev.codetrace.impact.service.ImpactReportService;
import dev.codetrace.impact.repository.ImpactReportRepository.Report;
import dev.codetrace.impact.service.ImpactReportService.Finding;
import dev.codetrace.impact.risk.RiskSignal.RiskFactor;
import dev.codetrace.jobs.controller.dto.JobResponse;
import dev.codetrace.common.web.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Requesting and reading change impact reports. */
@RestController
@Profile("api")
@RequestMapping("/api/v1")
public class ImpactReportController {
    public record ReportRequest(@NotBlank @Size(max = 64) String baseCommit, @NotBlank @Size(max = 64) String headCommit,
                                @Min(1) @Max(20) Integer depth) {
    }

    public record ReportView(UUID id, UUID repositoryId, String baseCommit, String headCommit, UUID baseSnapshotId,
                             UUID headSnapshotId, String algorithmVersion, int depth, String status, Integer riskScore,
                             String riskLevel, List<RiskFactor> riskFactors, Integer changedCount, Integer affectedCount,
                             Integer testsAffectedCount, Integer possibleCount, Integer unmappedHunks, Integer nonJavaFiles,
                             Boolean truncated, String failureReason, OffsetDateTime createdAt, OffsetDateTime completedAt) {
    }

    public record RequestedView(ReportView report, JobResponse job) {
    }

    private final ImpactReportService service;

    public ImpactReportController(ImpactReportService service) {
        this.service = service;
    }

    @PostMapping("/repositories/{id}/impact-reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RequestedView request(@PathVariable UUID id, @Valid @RequestBody ReportRequest req) {
        ImpactReportService.Requested r = service.request(id, req.baseCommit(), req.headCommit(), req.depth());
        return new RequestedView(view(r.report()), JobResponse.from(r.job()));
    }

    @GetMapping("/repositories/{id}/impact-reports")
    public List<ReportView> list(@PathVariable UUID id) {
        return service.list(id).stream().map(this::view).toList();
    }

    @GetMapping("/impact-reports/{reportId}")
    public ReportView get(@PathVariable UUID reportId) {
        return view(service.get(reportId));
    }

    @GetMapping("/impact-reports/{reportId}/findings")
    public Page<Finding> findings(@PathVariable UUID reportId, @RequestParam(required = false) String type,
                                  @RequestParam(required = false) String module,
                                  @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        return service.findings(reportId, type, module, limit, offset);
    }

    private ReportView view(Report r) {
        return new ReportView(r.id(), r.repositoryId(), r.baseCommit(), r.headCommit(), r.baseSnapshotId(), r.headSnapshotId(),
                r.algorithmVersion(), r.depth(), r.status(), r.riskScore(), r.riskLevel(), service.riskFactors(r),
                r.changedCount(), r.affectedCount(), r.testsAffectedCount(), r.possibleCount(), r.unmappedHunks(),
                r.nonJavaFiles(), r.truncated(), r.failureReason(), r.createdAt(), r.completedAt());
    }
}
