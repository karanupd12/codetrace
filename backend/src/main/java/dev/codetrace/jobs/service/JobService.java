package dev.codetrace.jobs.service;

import dev.codetrace.common.web.ApiException;
import dev.codetrace.jobs.model.IndexedRevision;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.jobs.model.JobOperation;
import dev.codetrace.jobs.model.JobStatus;
import dev.codetrace.jobs.queue.JobPublisher;
import dev.codetrace.jobs.model.JobEvent;
import dev.codetrace.jobs.model.JobStep;
import dev.codetrace.jobs.repository.JobEventRepository;
import dev.codetrace.jobs.repository.JobRepository;
import java.util.Map;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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

    /** At most this many progress steps are kept per job attempt. */
    public static final int MAX_EVENTS_PER_ATTEMPT = 25;
    public static final int MAX_EVENT_PAGE = 200;

    private final JobRepository jobs;
    private final JobPublisher publisher;
    private final JobEventRepository events;

    public JobService(JobRepository jobs, JobPublisher publisher, JobEventRepository events) {
        this.jobs = jobs;
        this.publisher = publisher;
        this.events = events;
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

    /** Marks an INDEX job finished with its counts; joins the caller's transaction so it commits with the results. */
    public void completeIndex(UUID jobId, int filesParsed, int filesSkipped, int symbols, String parserVersion,
                              int extractionVersion, int filesReused, UUID baseJobId) {
        jobs.markIndexSucceeded(jobId, filesParsed, filesSkipped, symbols, parserVersion, extractionVersion, filesReused,
                baseJobId);
    }

    /** The most recently finished indexes of other commits of the repository written by the given extractor. */
    public List<IndexedRevision> findRecentSucceededIndexes(UUID repositoryId, String otherThanCommit,
                                                            String parserVersion, int extractionVersion, int limit) {
        return jobs.findRecentSucceededIndexes(repositoryId, otherThanCommit, parserVersion, extractionVersion, limit);
    }

    public Optional<IndexedRevision> findSucceededIndex(UUID repositoryId, String commitHash) {
        return jobs.findSucceededIndex(repositoryId, commitHash);
    }

    /** Removes finished IMPACT jobs whose report is gone; returns how many. */
    public int pruneOrphanImpactJobs(int limit) {
        return jobs.deleteOrphanImpactJobs(limit);
    }

    /** Removes indexes of an older extractor that no graph uses, finished more than {@code days} ago; returns how many. */
    public int pruneStaleIndexes(int days, int limit) {
        return jobs.deleteStaleIndexes(IndexedRevision.CURRENT_EXTRACTION_VERSION, days, limit);
    }

    /** Removes failed SYNC, INDEX and GRAPH_BUILD jobs older than {@code days}; returns how many. */
    public int pruneFailed(int days, int limit) {
        return jobs.deleteFailedOlderThan(days, limit);
    }

    /**
     * Records a step of a running job in plain words. Written in its own transaction, so it is visible while the job
     * runs and survives if the job later fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void progress(Job job, JobStep step, String message, Map<String, ?> detail) {
        events.append(job.id(), job.attempts(), step.name(), message, detail, MAX_EVENTS_PER_ATTEMPT);
    }

    public void progress(Job job, JobStep step, String message) {
        progress(job, step, message, Map.of());
    }

    /** A job's steps after {@code afterSeq}, oldest first. */
    public List<JobEvent> events(UUID jobId, int afterSeq, int limit) {
        get(jobId);
        return events.forJob(jobId, Math.max(0, afterSeq), Math.max(1, Math.min(limit, MAX_EVENT_PAGE)));
    }

    /** The newest steps of a repository's jobs, newest first. */
    public List<JobEvent> activity(UUID repositoryId, int limit) {
        return events.forRepository(repositoryId, Math.max(1, Math.min(limit, MAX_EVENT_PAGE)));
    }
}
