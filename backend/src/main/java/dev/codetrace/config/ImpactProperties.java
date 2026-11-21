package dev.codetrace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounds for change detection and impact analysis. Every diff and traversal is capped by these. */
@ConfigurationProperties(prefix = "codetrace.impact")
public record ImpactProperties(
        /** A change touching more files than this is rejected instead of analysed partially. */
        @DefaultValue("1000") int maxChangedFiles,
        /** Changed hunks kept per file; further hunks are merged into one covering the remainder. */
        @DefaultValue("500") int maxHunksPerFile,
        /** Default and hard cap for how many dependency hops from a changed symbol are followed. */
        @DefaultValue("4") int defaultDepth,
        @DefaultValue("8") int maxDepth,
        /** A traversal stops (and says so) after reaching this many affected symbols. */
        @DefaultValue("5000") int maxAffected) {
}
