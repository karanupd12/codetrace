package dev.codetrace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounds for building and querying graph snapshots. Every query result and traversal is capped by these. */
@ConfigurationProperties(prefix = "codetrace.graph")
public record GraphProperties(
        @DefaultValue("500000") int maxNodes,
        @DefaultValue("2000000") int maxEdges,
        /** Hard cap for any paged result and for the number of edges in a neighbourhood query. */
        @DefaultValue("500") int maxQueryResults,
        /** Hard cap on traversal depth for neighbourhood queries. */
        @DefaultValue("6") int maxDepth,
        /** Hard cap on hops for path queries. */
        @DefaultValue("10") int maxPathDepth,
        /** A path search stops (and says so) after reaching this many distinct nodes. */
        @DefaultValue("20000") int maxPathVisited) {
}
