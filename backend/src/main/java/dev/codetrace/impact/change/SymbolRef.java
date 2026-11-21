package dev.codetrace.impact.change;

import java.util.UUID;

/**
 * A declaration (type, method or field) as stored in a graph snapshot. Identity for comparing two revisions is the
 * node type, qualified name and signature; the path and line numbers are attributes and may move.
 */
public record SymbolRef(UUID nodeId, String nodeType, String qualifiedName, String signature, String path, int startLine,
                        int endLine, String modifiers) {

    public String identity() {
        return nodeType + "|" + qualifiedName + "|" + signature;
    }

    boolean contains(SymbolRef other) {
        return other != this && other.startLine >= startLine && other.endLine <= endLine
                && !(other.startLine == startLine && other.endLine == endLine);
    }
}
