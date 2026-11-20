package dev.codetrace.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounds and policy for repository ingestion. Every limit here is enforced; none is advisory. */
@ConfigurationProperties(prefix = "codetrace.ingestion")
public record IngestionProperties(
        @DefaultValue("./data/workspaces") String workspaceDir,
        /** Local repositories must live under one of these roots. Empty = LOCAL sources disabled. */
        @DefaultValue List<String> localRoots,
        @DefaultValue({"github.com", "gitlab.com", "bitbucket.org"}) List<String> allowedHosts,
        @DefaultValue("50") int cloneDepth,
        @DefaultValue("120") int cloneTimeoutSeconds,
        @DefaultValue("209715200") long maxRepoBytes,
        @DefaultValue("50") int maxRevisions,
        @DefaultValue("5000") int maxJavaFiles,
        @DefaultValue("524288") long maxFileBytes,
        @DefaultValue("52428800") long maxTotalBytes,
        @DefaultValue("200000") int maxSymbols,
        @DefaultValue("300") int parseDeadlineSeconds,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("600") int staleRunningSeconds,
        @DefaultValue("30") int retryDelaySeconds,
        @DefaultValue({"target", "build", "out", "node_modules", ".git", ".gradle", ".idea", "dist"})
        List<String> ignoredDirs) {
}
