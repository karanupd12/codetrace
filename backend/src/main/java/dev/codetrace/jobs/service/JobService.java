package dev.codetrace.jobs.service;

import dev.codetrace.common.web.ApiException;
import dev.codetrace.jobs.model.IndexedRevision;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.jobs.model.JobOperation;
import dev.codetrace.jobs.model.JobStatus;
import dev.codetrace.jobs.queue.JobPublisher;
import dev.codetrace.jobs.repository.JobRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** How the rest of the application creates, queues and reads background jobs. */
@Service
public class JobService {
    /** A job and whether this request created it (so its work was queued now). */
    public record Submission(Job job, boolean created) {
    }

    private final JobRepository jobs;
    private final JobPublisher publisher;

    public JobService(JobRepository jobs, JobPublisher publisher) {
        this.jobs = jobs;
        this.publisher = publisher;
    }

    /** Idempotent: the same key always yields the same job; a new job is queued immediately. */
    public Submission submit(UUID repositoryId, JobOperation operation, String commitHash, String idempotencyKey) {
        Submission s = prepare(repositoryId, operation, commitHash, idempotencyKey);
        if (s.created()) publisher.publish(s.job().id());
        return s;
    }

    /**
     * Like {@link #submit} but does not queue the job, for callers that must store related state first (the worker
     * may pick the job up immediately); they call {@link #publish} afterwards.
     */
    public Submission prepare(UUID repositoryId, JobOperation operation, String commitHash, String idempotencyKey) {
        JobRepository.Created c = jobs.createOrGet(repositoryId, operation.name(), commitHash, idempotencyKey);
        return new Submission(c.job(), c.created());
    }

    /** Queues a job again if it failed or was dead-lettered. Returns whether it was requeued. */
    public boolean retryIfFailed(Job job) {
        return JobStatus.isFailed(job.status()) && rerun(job.id());
    }

    /** Queues a finished job again (any final state). Returns whether it was requeued. */
    public boolean rerun(UUID jobId) {
        if (!jobs.resetForRerun(jobId)) return false;
        publisher.publish(jobId);
        return true;
    }

    /** Puts an existing job back on the queue without changing its state, e.g. when its message may have been lost. */
    public void publish(UUID jobId) {
        publisher.publish(jobId);
    }

    public Job get(UUID jobId) {
        return jobs.find(jobId).orElseThrow(() -> ApiException.notFound("Job not found."));
    }

    public Optional<Job> find(UUID jobId) {
        return jobs.find(jobId);
    }

    public List<Job> listForRepository(UUID repositoryId, int limit) {
        return jobs.listForRepository(repositoryId, limit);
    }

    public Optional<IndexedRevision> findSucceededIndex(UUID repositoryId, String commitHash) {
        return jobs.findSucceededIndex(repositoryId, commitHash);
    }
}
