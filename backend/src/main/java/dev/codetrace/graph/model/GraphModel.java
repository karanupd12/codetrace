package dev.codetrace.graph.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure value types for the dependency graph. No Spring, no database: the resolver is testable in isolation. */
public final class GraphModel {
    private GraphModel() {
    }

    public enum NodeType { FILE, PACKAGE, TYPE, METHOD, FIELD }

    public enum EdgeType { CONTAINS, CALLS, REFERENCES, EXTENDS, IMPLEMENTS, IMPORTS, TESTS, OVERRIDES }

    /**
     * RESOLVED: evidence supports exactly one target. AMBIGUOUS: several candidates, one edge each, sharing an
     * alternative group. UNRESOLVED: no target in the repository (external library, unknown receiver, ...).
     */
    public enum Resolution { RESOLVED, AMBIGUOUS, UNRESOLVED }

    public record Node(UUID id, NodeType type, String qualifiedName, String signature, String simpleName,
                       String subtype, String path, int startLine, int endLine, String modifiers) {
    }

    /**
     * A directed relationship with provenance. {@code dstId} is null exactly when the edge is UNRESOLVED, in which case
     * {@code dstName} says what was being referred to. {@code occurrences} counts merged duplicate sites.
     */
    public record Edge(UUID srcId, EdgeType type, UUID dstId, String dstName, Resolution resolution, double confidence,
                       String evidenceKind, String reason, String alternativeGroup, String evidencePath,
                       int evidenceLine, int occurrences) {
    }

    // ---- input facts (as stored by the INDEX job) ----

    public record FileFact(String path, String packageName, String status) {
    }

    public record SymbolFact(String path, String kind, String subtype, String simpleName, String qualifiedName,
                             String signature, String parentQualifiedName, String modifiers, int startLine,
                             int endLine) {
    }

    public record ImportFact(String path, String name, boolean isStatic, boolean wildcard, int line) {
    }

    /** {@code argTypes} holds one hint per call argument ({@code ""} = unknown); empty for type references. */
    public record RefFact(String path, String ownerQualifiedName, String kind, String name, int argCount,
                          String receiverKind, String receiverType, List<String> argTypes, int line) {
    }

    public record Input(List<FileFact> files, List<SymbolFact> symbols, List<ImportFact> imports, List<RefFact> refs) {
    }

    public record Limits(int maxNodes, int maxEdges) {
    }

    public record Result(List<Node> nodes, List<Edge> edges, Map<String, Integer> stats) {
    }
}
