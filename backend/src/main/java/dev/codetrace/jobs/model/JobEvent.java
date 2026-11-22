package dev.codetrace.jobs.model;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** One reported step of a job, with the job's operation and commit for activity feeds. */
public record JobEvent(long id, UUID jobId, String operation, String commitHash, int attempt, int seq, OffsetDateTime at,
                       String step, String message, Map<String, Object> detail) {
}
