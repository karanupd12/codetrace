package dev.codetrace.source.controller.dto;

import dev.codetrace.source.model.SourceRepository;
import dev.codetrace.source.model.SourceType;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Never exposes server filesystem paths: originUrl is only set for GIT_URL sources. */
public record SourceRepositoryResponse(UUID id, SourceType sourceType, String displayName, String originUrl,
                                       String syncStatus, String defaultBranch, String headCommit,
                                       OffsetDateTime createdAt) {
    public static SourceRepositoryResponse from(SourceRepository r) {
        return new SourceRepositoryResponse(r.id(), r.sourceType(), r.displayName(),
                r.sourceType() == SourceType.GIT_URL ? r.location() : null, r.syncStatus(), r.defaultBranch(),
                r.headCommit(), r.createdAt());
    }
}
