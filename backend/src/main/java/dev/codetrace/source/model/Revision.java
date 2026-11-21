package dev.codetrace.source.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A commit discovered by sync. */
public record Revision(UUID id, String commitHash, String summary, OffsetDateTime authorTime) {
}
