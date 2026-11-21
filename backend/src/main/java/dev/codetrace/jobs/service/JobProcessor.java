package dev.codetrace.jobs.service;

import dev.codetrace.jobs.JobHandler;
import dev.codetrace.jobs.queue.JobPublisher;
import dev.codetrace.jobs.repository.JobRepository;
import dev.codetrace.config.IngestionProperties;
import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.common.web.ApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Runs one job. Safe to call any number of times for the same job id (Redis may redeliver):
 * the atomic DB claim lets exactly one caller run it, and finished jobs are never re-run.
 *
 * <p>The operation-specific work lives in {@link JobHandler} beans, discovered at startup. Returns normally for every
 * job outcome (success, failure, dead-letter, skipped duplicate), so the caller can acknowledge the message. It throws
 * only for infrastructure faults (e.g. database down) where the message must stay pending for redelivery.
 */
@Component
@Profile("worker")
public class JobProcessor {
    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    private final JobRepository jobs;
    private final JobPublisher publisher;
    private final IngestionProperties props;
    private final Map<String, JobHandler> handlers = new HashMap<>();

    public JobProcessor(JobRepository jobs, JobPublisher publisher, IngestionProperties props, List<JobHandler> handlerBeans) {
        this.jobs = jobs;
        this.publisher = publisher;
        this.props = props;
        for (JobHandler h : handlerBeans) {
            if (handlers.put(h.operation().name(), h) != null) {
                throw new IllegalStateException("Duplicate job handler for operation " + h.operation());
            }
        }
    }

    public void process(UUID jobId) {
        if (!jobs.claim(jobId, props.staleRunningSeconds())) {
            log.debug("job {} not claimable (finished, running elsewhere, or unknown); skipping", jobId);
            return;
        }
        Job job = jobs.find(jobId).orElseThrow();
        JobHandler handler = handlers.get(job.operation());
        if (handler == null) {
            handleFailure(job, null, Category.INTERNAL, "No handler for operation " + job.operation() + ".", false);
            return;
        }
        if (job.attempts() > props.maxAttempts()) {
            deadLetter(job, handler, "INTERNAL", "Attempts exhausted after worker interruptions.");
            return;
        }
        log.info("job {} {} attempt {}/{}", job.id(), job.operation(), job.attempts(), props.maxAttempts());
        try {
            handler.handle(job);
            jobs.markSucceededIfRunning(job.id());
        } catch (JobFailureException e) {
            handleFailure(job, handler, e.category(), e.getMessage(), true);
        } catch (ApiException e) {
            handleFailure(job, handler, Category.INVALID_SOURCE, e.getMessage(), true);
        } catch (DataAccessException e) {
            throw e; // infrastructure fault: leave the message pending
        } catch (RuntimeException e) {
            // Only the type is recorded: exception messages may contain paths or repository content.
            log.warn("job {} failed unexpectedly: {}", job.id(), e.getClass().getName());
            handleFailure(job, handler, Category.INTERNAL, JobFailureException.safeReason(e), true);
        }
    }

    private void handleFailure(Job job, JobHandler handler, Category category, String message, boolean mayRetry) {
        if (mayRetry && category.retryable() && job.attempts() < props.maxAttempts()) {
            log.info("job {} retryable failure ({}); will retry after delay", job.id(), category);
            jobs.markForRetry(job.id(), category.name(), message);
        } else if (mayRetry && category.retryable()) {
            deadLetter(job, handler, category.name(), message);
        } else {
            log.info("job {} failed permanently ({})", job.id(), category);
            jobs.markFailed(job.id(), category.name(), message);
            if (handler != null) handler.onPermanentFailure(job);
        }
    }

    private void deadLetter(Job job, JobHandler handler, String category, String message) {
        log.warn("job {} dead-lettered ({})", job.id(), category);
        jobs.markDeadLettered(job.id(), category, message);
        handler.onPermanentFailure(job);
        publisher.publishDead(job.id(), category);
    }
}
