package dev.codetrace.jobs;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.codetrace.ingestion.GitSourceManager;
import dev.codetrace.ingestion.IngestionException;
import dev.codetrace.ingestion.IngestionException.Category;
import dev.codetrace.ingestion.JavaIndexer;
import dev.codetrace.ingestion.RevisionDiscovery;
import dev.codetrace.jobs.JobDao.Job;
import dev.codetrace.repository.RepositoryDao;
import dev.codetrace.repository.RepositoryDao.Repo;
import dev.codetrace.repository.SourceType;
import dev.codetrace.support.GitFixtures;
import dev.codetrace.symbols.IndexResultWriter;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/** Retry, dead-letter and idempotency rules of the job state machine, with collaborators mocked. */
class JobProcessorTest {
    private final UUID jobId = UUID.randomUUID();
    private final UUID repoId = UUID.randomUUID();

    private JobDao jobs;
    private RepositoryDao repos;
    private GitSourceManager git;
    private JobPublisher publisher;
    private JobProcessor processor;

    @BeforeEach
    void setUp() {
        jobs = mock(JobDao.class);
        repos = mock(RepositoryDao.class);
        git = mock(GitSourceManager.class);
        publisher = mock(JobPublisher.class);
        processor = new JobProcessor(jobs, repos, git, mock(RevisionDiscovery.class), mock(JavaIndexer.class),
                mock(IndexResultWriter.class), publisher, GitFixtures.props(Path.of("ws"), List.of())); // maxAttempts=3
        when(repos.find(repoId)).thenReturn(Optional.of(new Repo(repoId, SourceType.GIT_URL,
                "https://github.com/o/r", "o/r", "PENDING", null, null, OffsetDateTime.now())));
    }

    private Job job(String op, int attempts) {
        return new Job(jobId, repoId, op, "RUNNING", op.equals("INDEX") ? "a".repeat(40) : null, attempts, null, null,
                OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null, null);
    }

    private void claimed(Job job) {
        when(jobs.claim(eq(jobId), anyInt())).thenReturn(true);
        when(jobs.find(jobId)).thenReturn(Optional.of(job));
    }

    @Test
    void redeliveredMessageForAnUnclaimableJobDoesNothing() {
        when(jobs.claim(eq(jobId), anyInt())).thenReturn(false); // finished, or running elsewhere

        processor.process(jobId);

        verifyNoInteractions(git, publisher);
        verify(jobs, never()).markFailed(any(), any(), any());
        verify(jobs, never()).markForRetry(any(), any(), any());
    }

    @Test
    void retryableFailureBeforeTheLimitSchedulesARetry() {
        claimed(job("SYNC", 1));
        when(git.open(any())).thenThrow(new IngestionException(Category.GIT_ERROR, "network"));

        processor.process(jobId);

        verify(jobs).markForRetry(eq(jobId), eq("GIT_ERROR"), anyString());
        verify(jobs, never()).markDeadLettered(any(), any(), any());
        verify(jobs, never()).markFailed(any(), any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void retryableFailureAtTheLimitIsDeadLettered() {
        claimed(job("SYNC", 3));
        when(git.open(any())).thenThrow(new IngestionException(Category.GIT_ERROR, "network"));

        processor.process(jobId);

        verify(jobs).markDeadLettered(eq(jobId), eq("GIT_ERROR"), anyString());
        verify(publisher).publishDead(jobId, "GIT_ERROR");
        verify(repos).markSyncFailed(repoId);
        verify(jobs, never()).markForRetry(any(), any(), any());
    }

    @Test
    void permanentFailuresAreNeverRetried() {
        for (Category c : List.of(Category.LIMIT_EXCEEDED, Category.INVALID_SOURCE, Category.REPOSITORY_UNAVAILABLE,
                Category.REVISION_NOT_FOUND, Category.TIMEOUT)) {
            jobs = mock(JobDao.class);
            git = mock(GitSourceManager.class); // fresh mock: a previous iteration's stubbing would throw on re-stub
            processor = new JobProcessor(jobs, repos, git, mock(RevisionDiscovery.class), mock(JavaIndexer.class),
                    mock(IndexResultWriter.class), publisher, GitFixtures.props(Path.of("ws"), List.of()));
            claimed(job("SYNC", 1));
            when(git.open(any())).thenThrow(new IngestionException(c, "nope"));

            processor.process(jobId);

            verify(jobs).markFailed(eq(jobId), eq(c.name()), anyString());
            verify(jobs, never()).markForRetry(any(), any(), any());
        }
        verify(repos, org.mockito.Mockito.atLeastOnce()).markSyncFailed(repoId);
    }

    @Test
    void unexpectedExceptionsAreRecordedByTypeOnly() {
        claimed(job("SYNC", 1));
        when(git.open(any())).thenThrow(new IllegalStateException("/secret/server/path leaked"));

        processor.process(jobId);

        verify(jobs).markForRetry(eq(jobId), eq("INTERNAL"),
                org.mockito.ArgumentMatchers.argThat(m -> !m.contains("/secret") && m.contains("IllegalStateException")));
    }

    @Test
    void staleJobsThatExhaustedAttemptsAreDeadLetteredWithoutRunning() {
        claimed(job("INDEX", 4)); // claim() already incremented past maxAttempts=3

        processor.process(jobId);

        verify(jobs).markDeadLettered(eq(jobId), anyString(), anyString());
        verify(publisher).publishDead(eq(jobId), anyString());
        verifyNoInteractions(git);
    }

    @Test
    void databaseFaultsPropagateSoTheMessageStaysPending() {
        claimed(job("SYNC", 1));
        when(git.open(any())).thenThrow(new QueryTimeoutException("db down"));

        assertThatThrownBy(() -> processor.process(jobId)).isInstanceOf(QueryTimeoutException.class);

        verify(jobs, never()).markFailed(any(), any(), any());
        verify(jobs, never()).markForRetry(any(), any(), any());
    }
}
