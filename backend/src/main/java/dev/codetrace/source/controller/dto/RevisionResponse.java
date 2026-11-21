package dev.codetrace.source.controller.dto;

import dev.codetrace.source.model.Revision;
import java.time.OffsetDateTime;

public record RevisionResponse(String commitHash, String summary, OffsetDateTime authorTime) {
    public static RevisionResponse from(Revision r) {
        return new RevisionResponse(r.commitHash(), r.summary(), r.authorTime());
    }
}
