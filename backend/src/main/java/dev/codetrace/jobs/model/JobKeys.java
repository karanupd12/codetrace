package dev.codetrace.jobs.model;

import java.util.UUID;

/** Idempotency keys: the same logical request always maps to the same job row. */
public final class JobKeys {
    private JobKeys() {
    }

    public static String sync(UUID repositoryId) {
        return "SYNC:" + repositoryId;
    }

    public static String index(UUID repositoryId, String commitHash) {
        return "INDEX:" + repositoryId + ":" + commitHash;
    }

    public static String graph(UUID repositoryId, String commitHash, String parserVersion, String builderVersion) {
        return "GRAPH:" + repositoryId + ":" + commitHash + ":" + parserVersion + ":" + builderVersion;
    }

    public static String impact(UUID repositoryId, UUID baseSnapshot, UUID headSnapshot, String algorithmVersion, int depth) {
        return "IMPACT:" + repositoryId + ":" + baseSnapshot + ":" + headSnapshot + ":" + algorithmVersion + ":" + depth;
    }
}
