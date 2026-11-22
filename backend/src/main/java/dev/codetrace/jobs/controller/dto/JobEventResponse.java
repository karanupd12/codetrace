package dev.codetrace.jobs.controller.dto;

import dev.codetrace.jobs.model.JobEvent;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** One step of a job's progress as returned by the API. */
public record JobEventResponse(UUID jobId, String operation, String commitHash, int attempt, int seq, OffsetDateTime at,
                               String step, String message, Map<String, Object> detail) {
    public static JobEventResponse from(JobEvent e) {
        return new JobEventResponse(e.jobId(), e.operation(), e.commitHash(), e.attempt(), e.seq(), e.at(), e.step(),
                e.message(), e.detail());
    }
}
