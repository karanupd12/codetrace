package dev.codetrace.ingestion.model;

import dev.codetrace.ingestion.repository.IndexResultRepository;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor;
import java.util.List;
import java.util.UUID;

/**
 * Everything one INDEX job extracted from one commit; persisted atomically by {@link IndexResultRepository}.
 * Files listed in {@code reused} were not parsed: their facts are copied from {@code baseJobId}, the earlier index
 * that holds the same blob.
 */
public record IndexResult(String parserVersion, int filesParsed, int filesSkipped, List<FileRecord> files,
                          List<SymbolRecord> symbols, List<ImportRecord> imports, List<ReferenceRecord> references,
                          List<DiagnosticRecord> diagnostics, UUID baseJobId, List<ReusedFile> reused) {

    public record ReferenceRecord(String path, JavaSymbolExtractor.Reference reference) {
    }


    public record FileRecord(String path, String packageName, String status, String skipReason, long sizeBytes,
                             String blobId) {
    }

    public record SymbolRecord(String path, JavaSymbolExtractor.Symbol symbol) {
    }

    public record ImportRecord(String path, JavaSymbolExtractor.Import imported) {
    }

    public record DiagnosticRecord(String path, JavaSymbolExtractor.Diagnostic diagnostic) {
    }

    /** A file whose facts are copied from the base index, where the same blob was stored under {@code basePath}. */
    public record ReusedFile(String path, String basePath) {
    }
}
