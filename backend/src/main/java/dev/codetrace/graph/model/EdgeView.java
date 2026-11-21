package dev.codetrace.graph.model;

import java.util.UUID;

/** An edge with its full provenance. {@code dstId} is null for UNRESOLVED edges; {@code dstName} then says what was sought. */
public record EdgeView(long id, UUID srcId, String srcName, UUID dstId, String dstName, String edgeType,
                       String resolution, double confidence, String evidenceKind, String reason, String alternativeGroup,
                       String evidencePath, int evidenceLine, int occurrences, String extractionMethod,
                       String resolverVersion) {
}
