package dev.codetrace.symbols.model;

/** A declaration extracted by an index job. */
public record IndexedSymbol(String path, String kind, String subtype, String simpleName, String qualifiedName,
                            String signature, String parentQualifiedName, String modifiers, int startLine, int endLine,
                            String extractionMethod, String parserVersion, String commitHash) {
}
