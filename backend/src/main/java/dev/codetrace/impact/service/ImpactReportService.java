package dev.codetrace.impact.service;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.codetrace.common.util.Bounds;
import dev.codetrace.common.util.Commits;
import dev.codetrace.common.util.JsonCodec;
import dev.codetrace.common.web.ApiException;
import dev.codetrace.common.web.Page;
import dev.codetrace.config.ImpactProperties;
import dev.codetrace.graph.repository.GraphSnapshotRepository.Snapshot;
import dev.codetrace.graph.service.GraphSnapshotService;
import dev.codetrace.impact.model.ImpactAnalysis.Hop;
import dev.codetrace.impact.repository.ImpactFindingRepository;
import dev.codetrace.impact.repository.ImpactReportRepository;
import dev.codetrace.impact.repository.ImpactReportRepository.Report;
import dev.codetrace.impact.risk.RiskSignal.RiskFactor;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.jobs.model.JobKeys;
import dev.codetrace.jobs.model.JobOperation;
import dev.codetrace.jobs.service.JobService;
import dev.codetrace.source.model.SourceRepository;
import dev.codetrace.source.service.SourceRepositoryService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Requesting and reading impact reports. The analysis itself runs in the worker as an IMPACT job. */
@Service
public class ImpactReportService {
    private static final Set<String> FINDING_TYPES = Set.of("CHANGED", "AFFECTED", "POSSIBLE");
    private static final TypeReference<List<RiskFactor>> RISK_FACTORS = new TypeReference<>() {
    };

    public record Requested(Report report, Job job) {
    }

    public record Finding(String findingType, int ordinal, String name, String nodeType, String path, int line,
                          String module, boolean test, String changeKind, Integer directDependents, Integer distance,
                          Boolean certain, Double confidence, String changedSymbol, List<Hop> chain) {
    }

    private final SourceRepositoryService repositories;
    private final GraphSnapshotService snapshots;
    private final ImpactReportRepository reports;
    private final ImpactFindingRepository findings;
    private final JobService jobs;
    private final ImpactProperties limits;
    private final JsonCodec json;

    public ImpactReportService(SourceRepositoryService repositories, GraphSnapshotService snapshots,
                               ImpactReportRepository reports, ImpactFindingRepository findings, JobService jobs,
                               ImpactProperties limits, JsonCodec json) {
        this.repositories = repositories;
        this.snapshots = snapshots;
        this.reports = reports;
        this.findings = findings;
        this.jobs = jobs;
        this.limits = limits;
        this.json = json;
    }

    /**
     * Idempotent: the same repository, base and head snapshots, algorithm version and depth always give one report and
     * one job. A completed report is returned as is; a failed one is queued again.
     */
    public Requested request(UUID repositoryId, String baseCommit, String headCommit, Integer depthParam) {
        SourceRepository repo = repositories.get(repositoryId);
        for (String[] c : List.of(new String[] {baseCommit, "baseCommit"}, new String[] {headCommit, "headCommit"})) {
            if (!Commits.isFullSha1(c[0])) throw ApiException.badRequest(c[1] + " must be a full 40-character commit id.");
        }
        if (Commits.normalize(baseCommit).equals(Commits.normalize(headCommit))) {
            throw ApiException.badRequest("baseCommit and headCommit must be different revisions.");
        }
        String base = repositories.requireRevision(repo, baseCommit, "baseCommit");
        String head = repositories.requireRevision(repo, headCommit, "headCommit");
        Snapshot baseSnap = publishedSnapshot(repo, base, "base");
        Snapshot headSnap = publishedSnapshot(repo, head, "head");
        int depth = Bounds.clamp(depthParam == null ? limits.defaultDepth() : depthParam, 1, limits.maxDepth());

        // the report row must exist before the worker can pick the job up, so the job is queued last
        JobService.Submission job = jobs.prepare(repo.id(), JobOperation.IMPACT, head,
                JobKeys.impact(repo.id(), baseSnap.id(), headSnap.id(), ImpactAnalyzer.ALGORITHM_VERSION, depth));
        ImpactReportRepository.Created created = reports.createOrGet(repo.id(), base, head, baseSnap.id(), headSnap.id(),
                ImpactAnalyzer.ALGORITHM_VERSION, depth, job.job().id());
        Report report = created.report();
        if (!report.completed()) {
            if (job.created() || created.created()) {
                jobs.publish(job.job().id());
            } else if (jobs.retryIfFailed(job.job())) {
                reports.requeue(report.id());
            }
        }
        return new Requested(reports.find(report.id()).orElseThrow(), jobs.get(report.jobId()));
    }

    public Report get(UUID reportId) {
        return reports.find(reportId).orElseThrow(() -> ApiException.notFound("Impact report not found."));
    }

    public List<Report> list(UUID repositoryId) {
        repositories.get(repositoryId);
        return reports.listForRepository(repositoryId, 50);
    }

    public Page<Finding> findings(UUID reportId, String type, String module, int limit, int offset) {
        Report r = completed(reportId);
        if (type != null && !FINDING_TYPES.contains(type)) {
            throw ApiException.badRequest("type must be one of CHANGED, AFFECTED, POSSIBLE.");
        }
        return findings.page(r.id(), type, module, Bounds.limit(limit, 500), Bounds.offset(offset));
    }

    /** A report whose findings may be read: only COMPLETED reports are exposed. */
    public Report completed(UUID reportId) {
        Report r = get(reportId);
        if (!r.completed()) {
            throw ApiException.conflict("Report is " + r.status() + "; its findings are available once it is completed.");
        }
        return r;
    }

    public List<RiskFactor> riskFactors(Report r) {
        return json.read(r.riskFactorsJson(), RISK_FACTORS, List.of());
    }

    private Snapshot publishedSnapshot(SourceRepository repo, String commit, String side) {
        return snapshots.latestPublished(repo.id(), commit).orElseThrow(() -> ApiException.conflict(
                "Build the dependency graph for revision " + Commits.shortHash(commit) + " (" + side + ") first."));
    }
}
