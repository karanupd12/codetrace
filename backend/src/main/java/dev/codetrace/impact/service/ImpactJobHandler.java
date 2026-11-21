package dev.codetrace.impact.service;

import dev.codetrace.impact.model.ImpactAnalysis;
import dev.codetrace.impact.repository.ImpactReportRepository;
import dev.codetrace.impact.repository.ImpactReportWriteRepository;
import dev.codetrace.impact.risk.RiskAssessor;
import dev.codetrace.impact.change.ChangeDetector;
import dev.codetrace.impact.change.ChangeSet;
import dev.codetrace.impact.repository.ImpactReportRepository.Report;
import dev.codetrace.impact.risk.RiskAssessor.RiskAssessment;
import dev.codetrace.ingestion.git.GitSourceManager;
import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.jobs.JobHandler;
import dev.codetrace.jobs.model.JobOperation;
import dev.codetrace.source.repository.SourceRepositoryRepository;
import dev.codetrace.source.repository.SourceRepositoryRepository.Repo;
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
    private final SourceRepositoryRepository repositories;
    private final GitSourceManager git;
    private final ChangeDetector detector;
    private final ImpactAnalyzer analyzer;
    private final RiskAssessor assessor;

    public ImpactJobHandler(ImpactReportRepository reports, ImpactReportWriteRepository writer, SourceRepositoryRepository repositories,
                            GitSourceManager git, ChangeDetector detector, ImpactAnalyzer analyzer, RiskAssessor assessor) {
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
        Report report = reports.findByJob(job.id()).orElseThrow(
                () -> new JobFailureException(Category.INVALID_SOURCE, "No impact report belongs to this job."));
        if (report.completed()) {
            log.info("job {} report {} already completed; nothing to do", job.id(), report.id());
            return;
        }
        if (!reports.markRunning(report.id())) return; // completed concurrently by another worker
        Repo repo = repositories.find(report.repositoryId()).orElseThrow(
                () -> new JobFailureException(Category.INVALID_SOURCE, "Repository no longer exists."));
        try (Repository repository = git.open(repo)) {
            ChangeSet changes = detector.detect(repository, report.baseCommit(), report.headCommit());
            ImpactAnalysis analysis = analyzer.analyze(report.baseSnapshotId(), report.headSnapshotId(), changes, report.depth());
            RiskAssessment risk = assessor.assess(analysis);
            writer.complete(report.id(), analysis, risk);
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
