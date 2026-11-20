package dev.codetrace.symbols;

import dev.codetrace.parser.JavaSymbolExtractor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a job's results in one transaction: previous rows for the job are deleted first, so replays and retries
 * can never produce duplicates or a half-written result. The job flips to SUCCEEDED in the same transaction.
 */
@Component
public class IndexResultWriter {
    private final JdbcTemplate jdbc;

    public IndexResultWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void write(UUID jobId, UUID repositoryId, String commitHash, IndexResult r) {
        for (String table : List.of("indexed_file", "code_symbol", "code_import", "code_reference", "parse_diagnostic")) {
            jdbc.update("delete from " + table + " where job_id = ?", jobId);
        }

        List<Object[]> files = new ArrayList<>();
        for (IndexResult.FileRecord f : r.files()) {
            files.add(new Object[] {jobId, f.path(), f.packageName(), f.status(), f.skipReason(), f.sizeBytes()});
        }
        jdbc.batchUpdate("""
                insert into indexed_file (job_id, path, package_name, status, skip_reason, size_bytes)
                values (?, ?, ?, ?, ?, ?)""", files);

        List<Object[]> symbols = new ArrayList<>();
        for (IndexResult.SymbolRecord s : r.symbols()) {
            JavaSymbolExtractor.Symbol y = s.symbol();
            symbols.add(new Object[] {UUID.randomUUID(), jobId, repositoryId, commitHash, s.path(), y.kind(),
                    y.subtype(), y.simpleName(), y.qualifiedName(), y.signature(), y.parentQualifiedName(),
                    y.modifiers(), y.startLine(), y.endLine(), JavaSymbolExtractor.EXTRACTION_METHOD,
                    r.parserVersion()});
        }
        jdbc.batchUpdate("""
                insert into code_symbol (id, job_id, repository_id, commit_hash, path, kind, subtype, simple_name,
                    qualified_name, signature, parent_qualified_name, modifiers, start_line, end_line,
                    extraction_method, parser_version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict on constraint uq_code_symbol_identity do nothing""", symbols);

        List<Object[]> imports = new ArrayList<>();
        for (IndexResult.ImportRecord i : r.imports()) {
            imports.add(new Object[] {jobId, i.path(), i.imported().name(), i.imported().isStatic(),
                    i.imported().wildcard(), i.imported().line()});
        }
        jdbc.batchUpdate("""
                insert into code_import (job_id, path, imported_name, is_static, is_wildcard, line)
                values (?, ?, ?, ?, ?, ?)""", imports);

        List<Object[]> refs = new ArrayList<>();
        for (IndexResult.ReferenceRecord rr : r.references()) {
            JavaSymbolExtractor.Reference x = rr.reference();
            refs.add(new Object[] {jobId, rr.path(), x.ownerQualifiedName(), x.kind(), x.name(), x.argCount(),
                    x.receiverKind(), x.receiverType(), x.line()});
        }
        jdbc.batchUpdate("""
                insert into code_reference (job_id, path, owner_qualified_name, kind, name, arg_count, receiver_kind,
                    receiver_type, line)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", refs);

        List<Object[]> diags = new ArrayList<>();
        for (IndexResult.DiagnosticRecord d : r.diagnostics()) {
            diags.add(new Object[] {jobId, d.path(), d.diagnostic().severity(), d.diagnostic().code(),
                    d.diagnostic().message(), d.diagnostic().line()});
        }
        jdbc.batchUpdate("""
                insert into parse_diagnostic (job_id, path, severity, code, message, line)
                values (?, ?, ?, ?, ?, ?)""", diags);

        int stored = jdbc.queryForObject("select count(*) from code_symbol where job_id = ?", Integer.class, jobId);
        jdbc.update("""
                update index_job set status = 'SUCCEEDED', files_parsed = ?, files_skipped = ?, symbols_extracted = ?,
                    parser_version = ?, extraction_version = ?, finished_at = now(), updated_at = now(),
                    error_category = null, error_message = null where id = ?""",
                r.filesParsed(), r.filesSkipped(), stored, r.parserVersion(), JavaSymbolExtractor.EXTRACTION_VERSION,
                jobId);
    }
}
