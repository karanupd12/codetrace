package dev.codetrace.jobs.controller.dto;

import dev.codetrace.jobs.model.Job;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A background job as returned by the API. */
public record JobResponse(UUID id, UUID repositoryId, String operation, String status, String commitHash, int attempts,
                          String errorCategory, String errorMessage, OffsetDateTime createdAt, OffsetDateTime startedAt,
                          OffsetDateTime finishedAt, Integer filesParsed, Integer filesSkipped, Integer symbolsExtracted,
                          String parserVersion, Integer filesReused, UUID baseJobId) {
    public static JobResponse from(Job j) {
        return new JobResponse(j.id(), j.repositoryId(), j.operation(), j.status(), j.commitHash(), j.attempts(),
                j.errorCategory(), j.errorMessage(), j.createdAt(), j.startedAt(), j.finishedAt(), j.filesParsed(),
                j.filesSkipped(), j.symbolsExtracted(), j.parserVersion(), j.filesReused(), j.baseJobId());
    }
}
