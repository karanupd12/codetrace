package dev.codetrace.jobs.service;

import dev.codetrace.jobs.JobHandler;
import dev.codetrace.jobs.model.JobOperation;
import dev.codetrace.jobs.queue.JobPublisher;
import dev.codetrace.jobs.repository.JobRepository;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.support.GitFixtures;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/** Retry, dead-letter, dispatch and idempotency rules of the job state machine, with collaborators mocked. */
class JobProcessorTest {
    private final UUID jobId = UUID.randomUUID();
    private final UUID repoId = UUID.randomUUID();

    private JobRepository jobs;
    private JobHandler handler;
    private JobPublisher publisher;
    private JobProcessor processor;

    @BeforeEach
    void setUp() {
        jobs = mock(JobRepository.class);
        publisher = mock(JobPublisher.class);
        handler = mock(JobHandler.class);
        when(handler.operation()).thenReturn(JobOperation.SYNC);
        processor = new JobProcessor(jobs, publisher, GitFixtures.props(Path.of("ws"), List.of()), List.of(handler)); // maxAttempts=3
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

        verify(handler, never()).handle(any());
        verifyNoInteractions(publisher);
        verify(jobs, never()).markFailed(any(), any(), any());
        verify(jobs, never()).markForRetry(any(), any(), any());
    }

    @Test
    void dispatchesToTheHandlerOwningTheOperationAndMarksSuccess() {
        JobHandler other = mock(JobHandler.class);
        when(other.operation()).thenReturn(JobOperation.INDEX);
        processor = new JobProcessor(jobs, publisher, GitFixtures.props(Path.of("ws"), List.of()), List.of(handler, other));
        claimed(job("INDEX", 1));

        processor.process(jobId);

        verify(other).handle(any());
        verify(handler, never()).handle(any());
        verify(jobs).markSucceededIfRunning(jobId);
    }

    @Test
    void duplicateHandlersForOneOperationFailFastAtStartup() {
        JobHandler twin = mock(JobHandler.class);
        when(twin.operation()).thenReturn(JobOperation.SYNC);

        assertThatThrownBy(() -> new JobProcessor(jobs, publisher, GitFixtures.props(Path.of("ws"), List.of()),
                List.of(handler, twin))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownOperationsFailPermanentlyWithoutRetry() {
        claimed(job("MYSTERY", 1));

        processor.process(jobId);

        verify(jobs).markFailed(eq(jobId), eq("INTERNAL"), anyString());
        verify(jobs, never()).markForRetry(any(), any(), any());
        verify(handler, never()).handle(any());
    }

    @Test
    void retryableFailureBeforeTheLimitSchedulesARetry() {
        claimed(job("SYNC", 1));
        doThrow(new JobFailureException(Category.GIT_ERROR, "network")).when(handler).handle(any());

        processor.process(jobId);

        verify(jobs).markForRetry(eq(jobId), eq("GIT_ERROR"), anyString());
        verify(jobs, never()).markDeadLettered(any(), any(), any());
        verify(jobs, never()).markFailed(any(), any(), any());
        verify(jobs, never()).markSucceededIfRunning(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void retryableFailureAtTheLimitIsDeadLettered() {
        Job j = job("SYNC", 3);
        claimed(j);
        doThrow(new JobFailureException(Category.GIT_ERROR, "network")).when(handler).handle(any());

        processor.process(jobId);

        verify(jobs).markDeadLettered(eq(jobId), eq("GIT_ERROR"), anyString());
        verify(publisher).publishDead(jobId, "GIT_ERROR");
        verify(handler).onPermanentFailure(j);
        verify(jobs, never()).markForRetry(any(), any(), any());
    }

    @Test
    void permanentFailuresAreNeverRetriedAndNotifyTheHandler() {
        for (Category c : List.of(Category.LIMIT_EXCEEDED, Category.INVALID_SOURCE, Category.REPOSITORY_UNAVAILABLE,
                Category.REVISION_NOT_FOUND, Category.TIMEOUT)) {
            jobs = mock(JobRepository.class);
            handler = mock(JobHandler.class); // fresh mocks: earlier stubbing would otherwise throw on re-stub
            when(handler.operation()).thenReturn(JobOperation.SYNC);
            processor = new JobProcessor(jobs, publisher, GitFixtures.props(Path.of("ws"), List.of()), List.of(handler));
            Job j = job("SYNC", 1);
            claimed(j);
            doThrow(new JobFailureException(c, "nope")).when(handler).handle(any());

            processor.process(jobId);

            verify(jobs).markFailed(eq(jobId), eq(c.name()), anyString());
            verify(jobs, never()).markForRetry(any(), any(), any());
            verify(handler).onPermanentFailure(j);
        }
    }

    @Test
    void unexpectedExceptionsAreRecordedByTypeOnly() {
        claimed(job("SYNC", 1));
        doThrow(new IllegalStateException("/secret/server/path leaked")).when(handler).handle(any());

        processor.process(jobId);

        verify(jobs).markForRetry(eq(jobId), eq("INTERNAL"),
                org.mockito.ArgumentMatchers.argThat(m -> !m.contains("/secret") && m.contains("IllegalStateException")));
    }

    @Test
    void staleJobsThatExhaustedAttemptsAreDeadLetteredWithoutRunning() {
        Job j = job("SYNC", 4); // claim() already incremented past maxAttempts=3
        claimed(j);

        processor.process(jobId);

        verify(jobs).markDeadLettered(eq(jobId), anyString(), anyString());
        verify(publisher).publishDead(eq(jobId), anyString());
        verify(handler).onPermanentFailure(j);
        verify(handler, never()).handle(any());
    }

    @Test
    void databaseFaultsPropagateSoTheMessageStaysPending() {
        claimed(job("SYNC", 1));
        doThrow(new QueryTimeoutException("db down")).when(handler).handle(any());

        assertThatThrownBy(() -> processor.process(jobId)).isInstanceOf(QueryTimeoutException.class);

        verify(jobs, never()).markFailed(any(), any(), any());
        verify(jobs, never()).markForRetry(any(), any(), any());
    }
}
