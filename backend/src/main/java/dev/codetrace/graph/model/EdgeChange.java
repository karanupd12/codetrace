package dev.codetrace.graph.model;

/**
 * An edge present in only one of two snapshots. A changed resolution (for example AMBIGUOUS to RESOLVED) shows as one
 * REMOVED and one ADDED edge. Evidence comes from the snapshot that holds the edge.
 */
public record EdgeChange(String change, String edgeType, String resolutionState, double confidence, String srcType,
                         String srcName, String srcSignature, String dstType, String dstName, String dstSignature,
                         String evidencePath, int evidenceLine) {
}
