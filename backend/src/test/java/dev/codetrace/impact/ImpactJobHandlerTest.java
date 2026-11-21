package dev.codetrace.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.codetrace.change.ChangeDetector;
import dev.codetrace.change.ChangeSet;
import dev.codetrace.impact.ImpactReportDao.Report;
import dev.codetrace.impact.RiskAssessor.Level;
import dev.codetrace.impact.RiskAssessor.RiskAssessment;
import dev.codetrace.ingestion.GitSourceManager;
import dev.codetrace.ingestion.IngestionException;
import dev.codetrace.ingestion.IngestionException.Category;
import dev.codetrace.jobs.JobDao.Job;
import dev.codetrace.repository.RepositoryDao;
import dev.codetrace.repository.RepositoryDao.Repo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class ImpactJobHandlerTest {
    private final ImpactReportDao reports = mock(ImpactReportDao.class);
    private final ImpactReportWriter writer = mock(ImpactReportWriter.class);
    private final RepositoryDao repositories = mock(RepositoryDao.class);
    private final GitSourceManager git = mock(GitSourceManager.class);
    private final ChangeDetector detector = mock(ChangeDetector.class);
    private final ImpactAnalyzer analyzer = mock(ImpactAnalyzer.class);
    private final RiskAssessor assessor = mock(RiskAssessor.class);
    private final ImpactJobHandler handler = new ImpactJobHandler(reports, writer, repositories, git, detector, analyzer, assessor);

    private final UUID reportId = UUID.randomUUID();
    private final UUID repoId = UUID.randomUUID();
    private final Job job = mock(Job.class);
    private final Repository gitRepo = mock(Repository.class);

    private static Report report(String status, UUID id, UUID repoId, UUID jobId) {
        return new Report(id, repoId, "a".repeat(40), "b".repeat(40), UUID.randomUUID(), UUID.randomUUID(), "impact-1", 4,
                jobId, status, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @BeforeEach
    void wire() {
        UUID jobId = UUID.randomUUID();
        when(job.id()).thenReturn(jobId);
        when(reports.findByJob(jobId)).thenReturn(Optional.of(report("REQUESTED", reportId, repoId, jobId)));
        when(reports.markRunning(reportId)).thenReturn(true);
        when(repositories.find(repoId)).thenReturn(Optional.of(mock(Repo.class)));
        when(git.open(any())).thenReturn(gitRepo);
    }

    @Test
    void operationNameIsImpact() {
        assertThat(handler.operation()).isEqualTo("IMPACT");
    }

    @Test
    void aCompletedReportIsLeftAlone() {
        UUID jobId = job.id();
        when(reports.findByJob(jobId)).thenReturn(Optional.of(report("COMPLETED", reportId, repoId, jobId)));

        handler.handle(job);

        verify(reports, never()).markRunning(any());
        verify(writer, never()).complete(any(), any(), any());
    }

    @Test
    void aJobWithoutAReportFailsPermanently() {
        when(reports.findByJob(job.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(job)).isInstanceOfSatisfying(IngestionException.class,
                e -> assertThat(e.category()).isEqualTo(Category.INVALID_SOURCE));
    }

    @Test
    void successStoresTheScoredAnalysis() {
        ChangeSet cs = new ChangeSet("a", "b", List.of());
        ImpactAnalysis analysis = new ImpactAnalysis("impact-1", 4, List.of(), List.of(), List.of(), 0, 0, false);
        RiskAssessment risk = new RiskAssessment(0, Level.LOW, List.of());
        when(detector.detect(eq(gitRepo), anyString(), anyString())).thenReturn(cs);
        when(analyzer.analyze(any(), any(), eq(cs), anyInt())).thenReturn(analysis);
        when(assessor.assess(analysis)).thenReturn(risk);

        handler.handle(job);

        verify(writer).complete(reportId, analysis, risk);
        verify(reports, never()).markFailed(any(), anyString());
    }

    @Test
    void anExpectedFailureIsRecordedWithItsSafeMessageAndRethrown() {
        when(detector.detect(any(), anyString(), anyString()))
                .thenThrow(new IngestionException(Category.REVISION_NOT_FOUND, "Revision aaaaaaa is not available in the repository."));

        assertThatThrownBy(() -> handler.handle(job)).isInstanceOf(IngestionException.class);

        verify(reports).markFailed(reportId, "Revision aaaaaaa is not available in the repository.");
        verify(writer, never()).complete(any(), any(), any());
    }

    @Test
    void anUnexpectedFailureRecordsOnlyTheExceptionType() {
        when(detector.detect(any(), anyString(), anyString())).thenThrow(new IllegalStateException("/srv/secret/path leaked"));

        assertThatThrownBy(() -> handler.handle(job)).isInstanceOf(IllegalStateException.class);

        verify(reports).markFailed(reportId, "Unexpected error (IllegalStateException).");
    }

    @Test
    void aDatabaseOutageIsRetriedNotRecordedAsAFailure() {
        when(detector.detect(any(), anyString(), anyString())).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> handler.handle(job)).isInstanceOf(DataAccessResourceFailureException.class);

        verify(reports, never()).markFailed(any(), anyString());
    }
}
