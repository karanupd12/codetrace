package dev.codetrace.impact;

import dev.codetrace.change.ChangedSymbolMapper.Kind;
import dev.codetrace.change.SymbolRef;
import java.util.List;
import java.util.UUID;

/**
 * Everything the analysis found for one change, before it is scored or rendered. Immutable; computed against a
 * specific base and head snapshot.
 */
public record ImpactAnalysis(String algorithmVersion, int depth, List<ChangedEntry> changed, List<AffectedSymbol> affected,
                             List<PossibleDependent> possible, int unmappedHunks, int nonJavaFiles, boolean truncated) {

    /** A symbol that changed. {@code base} is null when added, {@code head} is null when removed. */
    public record ChangedEntry(Kind kind, SymbolRef base, SymbolRef head, int directDependents) {
        public SymbolRef any() {
            return head != null ? head : base;
        }
    }

    /** One dependency edge on the chain from an affected symbol to the change; carries its own evidence. */
    public record Hop(String from, String to, String edgeType, String resolution, double confidence, String path, int line) {
    }

    /** A symbol that depends, directly or transitively, on something that changed. */
    public record AffectedSymbol(UUID nodeId, String nodeType, String qualifiedName, String signature, String path, int line,
                                 String module, boolean test, int distance, boolean certain, double confidence,
                                 String changedSymbol, List<Hop> chain) {
    }

    /** A call the resolver could not attribute to a receiver type, whose method name matches a changed method. */
    public record PossibleDependent(UUID nodeId, String qualifiedName, String path, int line, String changedSymbol) {
    }

    public long productionAffected() {
        return affected.stream().filter(a -> !a.test()).count();
    }

    public long testsAffected() {
        return affected.stream().filter(AffectedSymbol::test).count();
    }

    /** Module of a repository path: the part before {@code /src/}, or "(root)". */
    public static String moduleOf(String path) {
        int i = path.indexOf("/src/");
        if (i > 0) return path.substring(0, i);
        return "(root)";
    }

    public static boolean isTestPath(String path) {
        return path.startsWith("src/test/") || path.contains("/src/test/");
    }
}
