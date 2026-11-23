package dev.codetrace.impact.service;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.codetrace.common.util.Bounds;
import dev.codetrace.common.util.Commits;
import dev.codetrace.common.util.JsonCodec;
import dev.codetrace.common.web.ApiException;
import dev.codetrace.common.web.Page;
import dev.codetrace.config.ImpactProperties;
import dev.codetrace.graph.model.Snapshot;
import dev.codetrace.graph.service.GraphSnapshotService;
import dev.codetrace.impact.model.Finding;
import dev.codetrace.impact.model.ImpactSummary;
import dev.codetrace.impact.repository.ImpactFindingRepository;
import dev.codetrace.impact.repository.ImpactReportRepository;
import dev.codetrace.impact.model.ImpactReport;
import dev.codetrace.impact.risk.RiskSignal.RiskFactor;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.jobs.model.JobKeys;
import dev.codetrace.jobs.model.JobOperation;
import dev.codetrace.jobs.service.JobService;
import dev.codetrace.source.model.SourceRepository;
import dev.codetrace.source.service.SourceRepositoryService;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Requesting and reading impact reports. The analysis itself runs in the worker as an IMPACT job. */
@Service
public class ImpactReportService {
    private static final Set<String> FINDING_TYPES = Set.of("CHANGED", "AFFECTED", "POSSIBLE", "ENDPOINT");
    private static final TypeReference<List<RiskFactor>> RISK_FACTORS = new TypeReference<>() {
    };

    public record Requested(ImpactReport report, Job job) {
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
        ImpactReport report = created.report();
        if (!report.completed()) {
            if (job.created() || created.created()) {
                jobs.publish(job.job().id());
            } else if (jobs.retryIfFailed(job.job())) {
                reports.requeue(report.id());
            }
        }
        return new Requested(reports.find(report.id()).orElseThrow(), jobs.get(report.jobId()));
    }

    public ImpactReport get(UUID reportId) {
        return reports.find(reportId).orElseThrow(() -> ApiException.notFound("Impact report not found."));
    }

    public List<ImpactReport> list(UUID repositoryId) {
        repositories.get(repositoryId);
        return reports.listForRepository(repositoryId, 50);
    }

    /** A page of findings, optionally of one type, one module, and only tests ({@code test=true}) or only production code. */
    public Page<Finding> findings(UUID reportId, String type, String module, Boolean test, int limit, int offset) {
        ImpactReport r = completed(reportId);
        if (type != null && !FINDING_TYPES.contains(type)) {
            throw ApiException.badRequest("type must be one of CHANGED, AFFECTED, POSSIBLE, ENDPOINT.");
        }
        return findings.page(r.id(), type, module, test, Bounds.limit(limit, 500), Bounds.offset(offset));
    }

    /** Every finding of a completed report in report order; bounded by the analysis limits. */
    public List<Finding> allFindings(UUID reportId) {
        return findings.all(completed(reportId).id());
    }

    /** Counts over a completed report's findings: per type, per module, per distance and how many are uncertain. */
    public ImpactSummary summary(UUID reportId) {
        List<ImpactSummary.Group> groups = findings.groups(completed(reportId).id());
        Map<String, Long> byType = zeros("CHANGED", "AFFECTED", "POSSIBLE", "ENDPOINT");
        Map<String, Long> changed = zeros("ADDED", "MODIFIED", "REMOVED");
        Map<String, Long> endpoints = zeros("ADDED", "MODIFIED", "REMOVED", "AFFECTED");
        Map<String, long[]> modules = new HashMap<>();
        Map<Integer, long[]> distances = new TreeMap<>();
        long uncertain = 0;
        for (ImpactSummary.Group g : groups) {
            byType.merge(g.findingType(), g.count(), Long::sum);
            switch (g.findingType()) {
                case "CHANGED" -> changed.merge(g.changeKind() == null ? "MODIFIED" : g.changeKind(), g.count(), Long::sum);
                case "ENDPOINT" -> endpoints.merge(g.changeKind() == null ? "AFFECTED" : g.changeKind(), g.count(), Long::sum);
                case "AFFECTED" -> {
                    int side = g.test() ? 1 : 0;
                    modules.computeIfAbsent(g.module() == null ? "" : g.module(), m -> new long[2])[side] += g.count();
                    if (g.distance() != null) distances.computeIfAbsent(g.distance(), d -> new long[2])[side] += g.count();
                    if (Boolean.FALSE.equals(g.certain())) uncertain += g.count();
                }
                default -> {
                }
            }
        }
        List<ImpactSummary.ModuleCount> byModule = modules.entrySet().stream()
                .map(e -> new ImpactSummary.ModuleCount(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingLong(ImpactSummary.ModuleCount::total).reversed()
                        .thenComparing(ImpactSummary.ModuleCount::module))
                .toList();
        long others = byModule.stream().skip(ImpactSummary.MAX_MODULES).mapToLong(ImpactSummary.ModuleCount::total).sum();
        List<ImpactSummary.DistanceCount> byDistance = distances.entrySet().stream()
                .map(e -> new ImpactSummary.DistanceCount(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
        return new ImpactSummary(byType, changed, endpoints,
                byModule.stream().limit(ImpactSummary.MAX_MODULES).toList(), others, byDistance, uncertain);
    }

    private static Map<String, Long> zeros(String... keys) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String k : keys) m.put(k, 0L);
        return m;
    }

    /** A report whose findings may be read: only COMPLETED reports are exposed. */
    public ImpactReport completed(UUID reportId) {
        ImpactReport r = get(reportId);
        if (!r.completed()) {
            throw ApiException.conflict("Report is " + r.status() + "; its findings are available once it is completed.");
        }
        return r;
    }

    public List<RiskFactor> riskFactors(ImpactReport r) {
        return json.read(r.riskFactorsJson(), RISK_FACTORS, List.of());
    }

    /** The plain-language notes the analysis left for the reader. */
    public List<String> notes(ImpactReport r) {
        return json.read(r.notesJson(), NOTES, List.of());
    }

    private static final com.fasterxml.jackson.core.type.TypeReference<List<String>> NOTES = new com.fasterxml.jackson.core.type.TypeReference<>() {
    };

    private Snapshot publishedSnapshot(SourceRepository repo, String commit, String side) {
        return snapshots.latestPublished(repo.id(), commit).orElseThrow(() -> ApiException.conflict(
                "Build the dependency graph for revision " + Commits.shortHash(commit) + " (" + side + ") first."));
    }

    /** Removes reports superseded by a current-algorithm report of the same change; returns how many. */
    public int pruneSuperseded(int limit) {
        return reports.deleteSuperseded(ImpactAnalyzer.ALGORITHM_VERSION, limit);
    }

    /** Removes failed reports older than {@code days}; returns how many. */
    public int pruneFailed(int days, int limit) {
        return reports.deleteFailedOlderThan(days, limit);
    }
}
