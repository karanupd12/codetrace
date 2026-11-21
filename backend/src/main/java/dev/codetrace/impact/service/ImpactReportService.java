package dev.codetrace.impact.service;

import dev.codetrace.impact.repository.ImpactFindingRepository;
import dev.codetrace.impact.repository.ImpactReportRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codetrace.config.ImpactProperties;
import dev.codetrace.graph.repository.GraphSnapshotRepository;
import dev.codetrace.graph.repository.GraphSnapshotRepository.Snapshot;
import dev.codetrace.impact.model.ImpactAnalysis.Hop;
import dev.codetrace.impact.repository.ImpactReportRepository.Report;
import dev.codetrace.impact.risk.RiskSignal.RiskFactor;
import dev.codetrace.jobs.repository.JobRepository;
import dev.codetrace.jobs.repository.JobRepository.Job;
import dev.codetrace.jobs.queue.JobPublisher;
import dev.codetrace.source.repository.SourceRepositoryRepository;
import dev.codetrace.source.repository.SourceRepositoryRepository.Repo;
import dev.codetrace.common.web.ApiException;
import dev.codetrace.common.web.Page;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Requesting and reading impact reports. The analysis itself runs in the worker as an IMPACT job. */
@Service
public class ImpactReportService {
    private static final Pattern SHA1 = Pattern.compile("^[0-9a-fA-F]{40}$");
    private static final Set<String> FINDING_TYPES = Set.of("CHANGED", "AFFECTED", "POSSIBLE");

    public record Requested(Report report, Job job) {
    }

    public record Finding(String findingType, int ordinal, String name, String nodeType, String path, int line,
                          String module, boolean test, String changeKind, Integer directDependents, Integer distance,
                          Boolean certain, Double confidence, String changedSymbol, List<Hop> chain) {
    }

    private final SourceRepositoryRepository repos;
    private final GraphSnapshotRepository snapshots;
    private final ImpactReportRepository reports;
    private final ImpactFindingRepository findings;
    private final JobRepository jobs;
    private final JobPublisher publisher;
    private final ImpactProperties limits;
    private final ObjectMapper json;

    public ImpactReportService(SourceRepositoryRepository repos, GraphSnapshotRepository snapshots, ImpactReportRepository reports, ImpactFindingRepository findings,
                         JobRepository jobs, JobPublisher publisher, ImpactProperties limits, ObjectMapper json) {
        this.repos = repos;
        this.snapshots = snapshots;
        this.reports = reports;
        this.findings = findings;
        this.jobs = jobs;
        this.publisher = publisher;
        this.limits = limits;
        this.json = json;
    }

    /**
     * Idempotent: the same repository, base and head snapshots, algorithm version and depth always give one report and
     * one job. A completed report is returned as is; a failed one is queued again.
     */
    public Requested request(UUID repositoryId, String baseCommit, String headCommit, Integer depthParam) {
        Repo repo = repos.find(repositoryId).orElseThrow(() -> ApiException.notFound("Repository not found."));
        String base = commit(baseCommit, "baseCommit");
        String head = commit(headCommit, "headCommit");
        if (base.equals(head)) throw ApiException.badRequest("baseCommit and headCommit must be different revisions.");
        for (String c : List.of(base, head)) {
            if (!repos.revisionExists(repo.id(), c)) {
                throw ApiException.notFound("Revision " + c.substring(0, 7) + " is not known for this repository. List revisions first.");
            }
        }
        Snapshot baseSnap = snapshots.latestPublished(repo.id(), base).orElseThrow(
                () -> ApiException.conflict("Build the dependency graph for revision " + base.substring(0, 7) + " (base) first."));
        Snapshot headSnap = snapshots.latestPublished(repo.id(), head).orElseThrow(
                () -> ApiException.conflict("Build the dependency graph for revision " + head.substring(0, 7) + " (head) first."));
        int depth = Math.max(1, Math.min(depthParam == null ? limits.defaultDepth() : depthParam, limits.maxDepth()));

        JobRepository.Created job = jobs.createOrGet(repo.id(), ImpactJobHandler.OPERATION, head,
                JobRepository.impactKey(repo.id(), baseSnap.id(), headSnap.id(), ImpactAnalyzer.ALGORITHM_VERSION, depth));
        ImpactReportRepository.Created created = reports.createOrGet(repo.id(), base, head, baseSnap.id(), headSnap.id(),
                ImpactAnalyzer.ALGORITHM_VERSION, depth, job.job().id());
        Report report = created.report();
        if (!report.completed()) {
            if (job.created() || created.created()) {
                publisher.publish(job.job().id());
            } else if (("FAILED".equals(job.job().status()) || "DEAD_LETTERED".equals(job.job().status()))
                    && jobs.resetForRerun(job.job().id())) {
                reports.requeue(report.id());
                publisher.publish(job.job().id());
            }
        }
        return new Requested(reports.find(report.id()).orElseThrow(), jobs.find(report.jobId()).orElseThrow());
    }

    public Report get(UUID reportId) {
        return reports.find(reportId).orElseThrow(() -> ApiException.notFound("Impact report not found."));
    }

    public List<Report> list(UUID repositoryId) {
        repos.find(repositoryId).orElseThrow(() -> ApiException.notFound("Repository not found."));
        return reports.listForRepository(repositoryId, 50);
    }

    public Page<Finding> findings(UUID reportId, String type, String module, int limit, int offset) {
        Report r = completed(reportId);
        if (type != null && !FINDING_TYPES.contains(type)) {
            throw ApiException.badRequest("type must be one of CHANGED, AFFECTED, POSSIBLE.");
        }
        int lim = Math.max(1, Math.min(limit, 500));
        return findings.page(r.id(), type, module, lim, Math.max(0, offset));
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
        if (r.riskFactorsJson() == null) return List.of();
        try {
            return json.readValue(r.riskFactorsJson(), new TypeReference<List<RiskFactor>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored risk factors are unreadable.", e);
        }
    }

    private static String commit(String value, String field) {
        if (value == null || !SHA1.matcher(value.trim()).matches()) {
            throw ApiException.badRequest(field + " must be a full 40-character commit id.");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
