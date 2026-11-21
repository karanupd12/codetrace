package dev.codetrace.impact;

import dev.codetrace.change.ChangeDetector;
import dev.codetrace.change.ChangeSet;
import dev.codetrace.impact.ImpactReportDao.Report;
import dev.codetrace.impact.RiskAssessor.RiskAssessment;
import dev.codetrace.ingestion.GitSourceManager;
import dev.codetrace.ingestion.IngestionException;
import dev.codetrace.ingestion.IngestionException.Category;
import dev.codetrace.jobs.JobDao.Job;
import dev.codetrace.jobs.JobHandler;
import dev.codetrace.repository.RepositoryDao;
import dev.codetrace.repository.RepositoryDao.Repo;
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
    public static final String OPERATION = "IMPACT";
    private static final Logger log = LoggerFactory.getLogger(ImpactJobHandler.class);

    private final ImpactReportDao reports;
    private final ImpactReportWriter writer;
    private final RepositoryDao repositories;
    private final GitSourceManager git;
    private final ChangeDetector detector;
    private final ImpactAnalyzer analyzer;
    private final RiskAssessor assessor;

    public ImpactJobHandler(ImpactReportDao reports, ImpactReportWriter writer, RepositoryDao repositories,
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
    public String operation() {
        return OPERATION;
    }

    @Override
    public void handle(Job job) {
        Report report = reports.findByJob(job.id()).orElseThrow(
                () -> new IngestionException(Category.INVALID_SOURCE, "No impact report belongs to this job."));
        if (report.completed()) {
            log.info("job {} report {} already completed; nothing to do", job.id(), report.id());
            return;
        }
        if (!reports.markRunning(report.id())) return; // completed concurrently by another worker
        Repo repo = repositories.find(report.repositoryId()).orElseThrow(
                () -> new IngestionException(Category.INVALID_SOURCE, "Repository no longer exists."));
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
            String reason = e instanceof IngestionException ie ? ie.getMessage()
                    : "Unexpected error (" + e.getClass().getSimpleName() + ").";
            reports.markFailed(report.id(), reason);
            throw e;
        }
    }
}
