package dev.codetrace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounds for building and querying graph snapshots. */
@ConfigurationProperties(prefix = "codetrace.graph")
public record GraphProperties(
        @DefaultValue("500000") int maxNodes,
        @DefaultValue("2000000") int maxEdges,
        /** Hard cap for any paged or neighbourhood query result. */
        @DefaultValue("500") int maxQueryResults,
        /** Hard cap on traversal depth for neighbourhood and path queries. */
        @DefaultValue("6") int maxDepth) {
}
