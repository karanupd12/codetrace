package dev.codetrace.jobs.model;

import java.util.UUID;

/** The finished INDEX job whose stored facts a graph build for a commit reads. */
public record IndexedRevision(UUID jobId, String parserVersion, Integer extractionVersion) {
    /**
     * The version of the facts the current extractor produces. Bumped whenever the set of extracted facts changes; an
     * index written by an older extractor is re-run on request and cannot feed a graph build.
     */
    public static final int CURRENT_EXTRACTION_VERSION = 4;

    /** Whether this index was written by the current extractor. */
    public boolean isCurrent() {
        return extractionVersion != null && extractionVersion >= CURRENT_EXTRACTION_VERSION;
    }
}
