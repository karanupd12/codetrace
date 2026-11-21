package dev.codetrace.jobs;

import dev.codetrace.jobs.model.JobOperation;
import dev.codetrace.jobs.service.JobProcessor;
import dev.codetrace.jobs.model.Job;

/**
 * Extension point for background work. One implementation per job operation; {@link JobProcessor} dispatches by
 * {@link #operation()}, so a new kind of job is a new handler bean, with no change to the processor.
 *
 * <p>Handlers throw {@code JobFailureException} (categorized, safe messages) for expected failures. Anything else is
 * recorded by exception type only. A handler may finish the job itself (e.g. inside its result transaction); if it
 * returns while the job is still RUNNING the processor marks it SUCCEEDED.
 */
public interface JobHandler {
    /** The {@code index_job.operation} value this handler owns, e.g. {@code SYNC}. */
    JobOperation operation();

    void handle(Job job);

    /** Called after the job failed permanently or was dead-lettered, to roll back operation-specific state. */
    default void onPermanentFailure(Job job) {
    }
}
