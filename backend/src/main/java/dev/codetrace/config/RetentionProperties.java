package dev.codetrace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** What the worker deletes on its daily clean-up, and how much per run. */
@ConfigurationProperties(prefix = "codetrace.retention")
public record RetentionProperties(
        @DefaultValue("true") boolean enabled,
        /** Spring cron expression for the clean-up; daily at 03:30 by default. */
        @DefaultValue("0 30 3 * * *") String cron,
        /** Failed graph builds, analyses and jobs are kept this long so their reasons can be read. */
        @DefaultValue("7") int failedAfterDays,
        /** An index written by an older extractor and used by no graph is kept this long. */
        @DefaultValue("30") int staleIndexAfterDays,
        /** At most this many rows of each kind are deleted per run, so a run stays short. */
        @DefaultValue("200") int batchSize) {
}
