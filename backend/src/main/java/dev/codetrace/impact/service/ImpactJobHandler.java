package dev.codetrace.impact.service;

import dev.codetrace.impact.model.ImpactAnalysis;
import dev.codetrace.impact.repository.ImpactReportRepository;
import dev.codetrace.impact.repository.ImpactReportWriteRepository;
import dev.codetrace.impact.risk.RiskAssessor;
import dev.codetrace.impact.change.ChangeDetector;
import dev.codetrace.impact.change.ChangeSet;
import dev.codetrace.impact.model.ImpactReport;
import dev.codetrace.impact.risk.RiskAssessor.RiskAssessment;
import dev.codetrace.ingestion.git.GitSourceManager;
import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.jobs.JobHandler;
import dev.codetrace.jobs.model.JobOperation;
import dev.codetrace.source.service.SourceRepositoryService;
import dev.codetrace.source.model.SourceRepository;
import dev.codetrace.jobs.model.JobStep;
import dev.codetrace.jobs.service.JobService;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * IMPACT: compare two commits, analyse the impact against their published snapshots, score it and store the completed
 * report. Runs in the worker because only the worker holds the Git workspaces. Safe to run again: a completed report
 * is left untouched, a failed one is recomputed from scratch.
 */
@Component
@Profile("worker")
public class ImpactJobHandler implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(ImpactJobHandler.class);

    private final ImpactReportRepository reports;
    private final ImpactReportWriteRepository writer;
    private final SourceRepositoryService repositories;
    private final GitSourceManager git;
    private final ChangeDetector detector;
    private final ImpactAnalyzer analyzer;
    private final RiskAssessor assessor;
    private final JobService jobs;

    public ImpactJobHandler(ImpactReportRepository reports, ImpactReportWriteRepository writer, SourceRepositoryService repositories,
                            GitSourceManager git, ChangeDetector detector, ImpactAnalyzer analyzer, RiskAssessor assessor,
                            JobService jobs) {
        this.jobs = jobs;
        this.reports = reports;
        this.writer = writer;
        this.repositories = repositories;
        this.git = git;
        this.detector = detector;
        this.analyzer = analyzer;
        this.assessor = assessor;
    }

    @Override
    public JobOperation operation() {
        return JobOperation.IMPACT;
    }

    @Override
    public void handle(Job job) {
        ImpactReport report = reports.findByJob(job.id()).orElseThrow(
                () -> new JobFailureException(Category.INVALID_SOURCE, "No impact report belongs to this job."));
        if (report.completed()) {
            log.info("job {} report {} already completed; nothing to do", job.id(), report.id());
            return;
        }
        if (!reports.markRunning(report.id())) return; // completed concurrently by another worker
        SourceRepository repo = repositories.find(report.repositoryId()).orElseThrow(
                () -> new JobFailureException(Category.INVALID_SOURCE, "Repository no longer exists."));
        try (Repository repository = git.open(repo, List.of(report.baseCommit(), report.headCommit()))) {
            jobs.progress(job, JobStep.COMPARING, "Comparing the two versions");
            ChangeSet changes = detector.detect(repository, report.baseCommit(), report.headCommit());
            jobs.progress(job, JobStep.CHANGES_FOUND, "Found changes in " + changes.javaFiles().size() + " Java file"
                    + (changes.javaFiles().size() == 1 ? "" : "s"), Map.of("files", changes.javaFiles().size()));
            jobs.progress(job, JobStep.TRACING, "Tracing what depends on the changed code");
            ImpactAnalysis analysis = analyzer.analyze(report.baseSnapshotId(), report.headSnapshotId(), changes, report.depth());
            jobs.progress(job, JobStep.SCORING, "Scoring the risk: " + analysis.changed().size() + " changed, "
                    + analysis.affected().size() + " may be affected",
                    Map.of("changed", analysis.changed().size(), "affected", analysis.affected().size()));
            RiskAssessment risk = assessor.assess(analysis);
            jobs.progress(job, JobStep.WRITING_REPORT, "Writing the report");
            writer.complete(report.id(), analysis, risk);
            String level = risk.level().name().charAt(0) + risk.level().name().substring(1).toLowerCase(java.util.Locale.ROOT);
            jobs.progress(job, JobStep.DONE, "Analysis ready: " + level + " risk", Map.of("score", risk.score()));
            log.info("job {} completed report {}: {} changed, {} affected, risk {} ({})", job.id(), report.id(),
                    analysis.changed().size(), analysis.affected().size(), risk.score(), risk.level());
        } catch (DataAccessException e) {
            throw e; // infrastructure fault: the retried job recomputes
        } catch (RuntimeException e) {
            String reason = JobFailureException.safeReason(e);
            reports.markFailed(report.id(), reason);
            throw e;
        }
    }
}
