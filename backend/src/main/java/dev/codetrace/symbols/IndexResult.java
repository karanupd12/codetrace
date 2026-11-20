package dev.codetrace.symbols;

import dev.codetrace.parser.JavaSymbolExtractor;
import java.util.List;

/** Everything one INDEX job extracted from one commit; persisted atomically by {@link IndexResultWriter}. */
public record IndexResult(String parserVersion, int filesParsed, int filesSkipped, List<FileRecord> files,
                          List<SymbolRecord> symbols, List<ImportRecord> imports, List<ReferenceRecord> references,
                          List<DiagnosticRecord> diagnostics) {

    public record ReferenceRecord(String path, JavaSymbolExtractor.Reference reference) {
    }


    public record FileRecord(String path, String packageName, String status, String skipReason, long sizeBytes) {
    }

    public record SymbolRecord(String path, JavaSymbolExtractor.Symbol symbol) {
    }

    public record ImportRecord(String path, JavaSymbolExtractor.Import imported) {
    }

    public record DiagnosticRecord(String path, JavaSymbolExtractor.Diagnostic diagnostic) {
    }
}
