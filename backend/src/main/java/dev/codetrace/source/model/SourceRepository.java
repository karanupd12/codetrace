package dev.codetrace.source.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A registered Git repository. {@code location} is a server-side detail and is only exposed for GIT_URL sources. */
public record SourceRepository(UUID id, SourceType sourceType, String location, String displayName, String syncStatus,
                               String defaultBranch, String headCommit, OffsetDateTime createdAt) {
}
