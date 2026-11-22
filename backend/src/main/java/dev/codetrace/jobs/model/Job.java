package dev.codetrace.jobs.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A background job as stored in {@code index_job}. */
public record Job(UUID id, UUID repositoryId, String operation, String status, String commitHash, int attempts,
                  String errorCategory, String errorMessage, OffsetDateTime createdAt, OffsetDateTime startedAt,
                  OffsetDateTime finishedAt, Integer filesParsed, Integer filesSkipped, Integer symbolsExtracted,
                  String parserVersion, Integer filesReused, UUID baseJobId) {
}
