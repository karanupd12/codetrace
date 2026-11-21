package dev.codetrace.symbols.model;

/** A parser problem reported by an index job. */
public record ParseDiagnostic(String path, String severity, String code, String message, int line) {
}
