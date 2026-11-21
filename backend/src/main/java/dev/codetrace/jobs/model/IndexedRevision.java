package dev.codetrace.jobs.model;

import java.util.UUID;

/** The finished INDEX job whose stored facts a graph build for a commit reads. */
public record IndexedRevision(UUID jobId, String parserVersion, Integer extractionVersion) {
}
