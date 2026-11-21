package dev.codetrace.graph.model;

import java.util.UUID;

/** A stored graph node. */
public record NodeView(UUID id, String nodeType, String qualifiedName, String signature, String simpleName,
                       String subtype, String path, int startLine, int endLine, String modifiers,
                       String extractionMethod, String parserVersion) {
}
