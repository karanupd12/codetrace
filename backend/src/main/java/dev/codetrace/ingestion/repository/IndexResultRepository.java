package dev.codetrace.ingestion.repository;

import dev.codetrace.common.util.ArgumentTypes;
import dev.codetrace.ingestion.model.IndexResult;
import dev.codetrace.ingestion.model.ReusableIndex;
import dev.codetrace.ingestion.model.ReusableIndex.ReusableFile;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes a job's results in one transaction: previous rows for the job are deleted first, so replays and retries
 * can never produce duplicates or a half-written result. The job flips to SUCCEEDED in the same transaction.
 */
@Repository
public class IndexResultRepository {
    private final JdbcTemplate jdbc;

    public IndexResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Replaces this job's stored results; returns the number of symbols stored. Call inside a transaction. */
    public int write(UUID jobId, UUID repositoryId, String commitHash, IndexResult r) {
        for (String table : List.of("indexed_file", "code_symbol", "code_import", "code_reference", "parse_diagnostic",
                "code_endpoint")) {
            jdbc.update("delete from " + table + " where job_id = ?", jobId);
        }

        List<Object[]> files = new ArrayList<>();
        for (IndexResult.FileRecord f : r.files()) {
            files.add(new Object[] {jobId, f.path(), f.packageName(), f.status(), f.skipReason(), f.sizeBytes(),
                    f.blobId()});
        }
        jdbc.batchUpdate("""
                insert into indexed_file (job_id, path, package_name, status, skip_reason, size_bytes, blob_id)
                values (?, ?, ?, ?, ?, ?, ?)""", files);

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
                    x.receiverKind(), x.receiverType(), ArgumentTypes.encode(x.argTypes()), x.line()});
        }
        jdbc.batchUpdate("""
                insert into code_reference (job_id, path, owner_qualified_name, kind, name, arg_count, receiver_kind,
                    receiver_type, arg_types, line)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", refs);

        List<Object[]> diags = new ArrayList<>();
        for (IndexResult.DiagnosticRecord d : r.diagnostics()) {
            diags.add(new Object[] {jobId, d.path(), d.diagnostic().severity(), d.diagnostic().code(),
                    d.diagnostic().message(), d.diagnostic().line()});
        }
        jdbc.batchUpdate("""
                insert into parse_diagnostic (job_id, path, severity, code, message, line)
                values (?, ?, ?, ?, ?, ?)""", diags);

        List<Object[]> endpoints = new ArrayList<>();
        for (IndexResult.EndpointRecord e : r.endpoints()) {
            JavaSymbolExtractor.Endpoint x = e.endpoint();
            endpoints.add(new Object[] {jobId, e.path(), x.handlerQualifiedName(), x.httpMethod(), x.route(), x.framework(),
                    x.reason(), x.line()});
        }
        jdbc.batchUpdate("""
                insert into code_endpoint (job_id, path, handler_qualified_name, http_method, route, framework, reason, line)
                values (?, ?, ?, ?, ?, ?, ?, ?)""", endpoints);

        if (!r.reused().isEmpty()) copyReused(jobId, repositoryId, commitHash, r);

        return jdbc.queryForObject("select count(*) from code_symbol where job_id = ?", Integer.class, jobId);
    }

    /**
     * The parsed files of a finished index that carry a blob id, keyed by blob, with their stored fact counts. When
     * several paths hold the same blob, the first path in sort order is used.
     */
    public ReusableIndex reusable(UUID baseJobId) {
        Map<String, ReusableFile> byBlob = new HashMap<>();
        jdbc.query("""
                select f.path, f.blob_id, f.package_name, f.status,
                       coalesce(s.n, 0) as symbols, coalesce(r.n, 0) as refs
                  from indexed_file f
                  left join (select path, count(*) as n from code_symbol where job_id = ? group by path) s
                         on s.path = f.path
                  left join (select path, count(*) as n from code_reference where job_id = ? group by path) r
                         on r.path = f.path
                 where f.job_id = ? and f.blob_id <> '' and f.status in ('PARSED', 'PARTIAL', 'FAILED')
                 order by f.path""", rs -> {
            byBlob.putIfAbsent(rs.getString("blob_id"), new ReusableFile(rs.getString("path"),
                    rs.getString("package_name"), rs.getString("status"), rs.getInt("symbols"), rs.getInt("refs")));
        }, baseJobId, baseJobId, baseJobId);
        return new ReusableIndex(baseJobId, byBlob);
    }

    /** For each of {@code jobIds}, how many of its parsed files hold one of {@code blobIds}. */
    public Map<UUID, Integer> sharedBlobCounts(List<UUID> jobIds, Collection<String> blobIds) {
        Map<UUID, Integer> out = new HashMap<>();
        jdbc.query("""
                select job_id, count(*) as n from indexed_file
                 where job_id = any(?) and blob_id = any(?) and status in ('PARSED', 'PARTIAL', 'FAILED')
                 group by job_id""",
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("uuid", jobIds.toArray()));
                    ps.setArray(2, ps.getConnection().createArrayOf("text", blobIds.toArray()));
                },
                rs -> {
                    out.put(rs.getObject("job_id", UUID.class), rs.getInt("n"));
                });
        return out;
    }

    /** Copies the facts of reused files from the base index, under each file's path in this commit. */
    private void copyReused(UUID jobId, UUID repositoryId, String commitHash, IndexResult r) {
        String[] paths = r.reused().stream().map(IndexResult.ReusedFile::path).toArray(String[]::new);
        String[] basePaths = r.reused().stream().map(IndexResult.ReusedFile::basePath).toArray(String[]::new);
        String mapping = "unnest(?::text[], ?::text[]) as m(path, base_path)";

        jdbc.update("""
                insert into code_symbol (id, job_id, repository_id, commit_hash, path, language, kind, subtype,
                    simple_name, qualified_name, signature, parent_qualified_name, modifiers, start_line, end_line,
                    extraction_method, parser_version)
                select gen_random_uuid(), ?, ?, ?, m.path, s.language, s.kind, s.subtype, s.simple_name,
                       s.qualified_name, s.signature, s.parent_qualified_name, s.modifiers, s.start_line, s.end_line,
                       s.extraction_method, s.parser_version
                  from code_symbol s join %s on s.path = m.base_path where s.job_id = ?
                on conflict on constraint uq_code_symbol_identity do nothing""".formatted(mapping),
                ps -> bind(ps, jobId, repositoryId, commitHash, paths, basePaths, r.baseJobId()));
        jdbc.update("""
                insert into code_import (job_id, path, imported_name, is_static, is_wildcard, line)
                select ?, m.path, i.imported_name, i.is_static, i.is_wildcard, i.line
                  from code_import i join %s on i.path = m.base_path where i.job_id = ?""".formatted(mapping),
                ps -> bind(ps, jobId, null, null, paths, basePaths, r.baseJobId()));
        jdbc.update("""
                insert into code_reference (job_id, path, owner_qualified_name, kind, name, arg_count, receiver_kind,
                    receiver_type, arg_types, line)
                select ?, m.path, x.owner_qualified_name, x.kind, x.name, x.arg_count, x.receiver_kind,
                       x.receiver_type, x.arg_types, x.line
                  from code_reference x join %s on x.path = m.base_path where x.job_id = ?""".formatted(mapping),
                ps -> bind(ps, jobId, null, null, paths, basePaths, r.baseJobId()));
        jdbc.update("""
                insert into parse_diagnostic (job_id, path, severity, code, message, line)
                select ?, m.path, d.severity, d.code, d.message, d.line
                  from parse_diagnostic d join %s on d.path = m.base_path where d.job_id = ?""".formatted(mapping),
                ps -> bind(ps, jobId, null, null, paths, basePaths, r.baseJobId()));
        jdbc.update("""
                insert into code_endpoint (job_id, path, handler_qualified_name, http_method, route, framework, reason, line)
                select ?, m.path, x.handler_qualified_name, x.http_method, x.route, x.framework, x.reason, x.line
                  from code_endpoint x join %s on x.path = m.base_path where x.job_id = ?""".formatted(mapping),
                ps -> bind(ps, jobId, null, null, paths, basePaths, r.baseJobId()));
    }

    /** Binds the target job (plus repository and commit when given), the path mapping and the base job, in order. */
    private static void bind(PreparedStatement ps, UUID jobId, UUID repositoryId, String commitHash, String[] paths,
                             String[] basePaths, UUID baseJobId) throws SQLException {
        int i = 1;
        ps.setObject(i++, jobId);
        if (repositoryId != null) {
            ps.setObject(i++, repositoryId);
            ps.setString(i++, commitHash);
        }
        ps.setArray(i++, ps.getConnection().createArrayOf("text", paths));
        ps.setArray(i++, ps.getConnection().createArrayOf("text", basePaths));
        ps.setObject(i, baseJobId);
    }
}
