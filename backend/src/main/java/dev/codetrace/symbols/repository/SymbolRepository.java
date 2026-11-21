package dev.codetrace.symbols.repository;

import dev.codetrace.common.util.SqlLike;
import dev.codetrace.common.web.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Bounded read queries over a job's extracted symbols and diagnostics. */
@Repository
public class SymbolRepository {
    public static final int MAX_LIMIT = 200;

    public record SymbolView(String path, String kind, String subtype, String simpleName, String qualifiedName,
                             String signature, String parentQualifiedName, String modifiers, int startLine,
                             int endLine, String extractionMethod, String parserVersion, String commitHash) {
    }

    public record DiagnosticView(String path, String severity, String code, String message, int line) {
    }

    private final JdbcClient jdbc;

    public SymbolRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Page<SymbolView> search(UUID jobId, String kind, String query, int limit, int offset) {
        int lim = Math.max(1, Math.min(limit, MAX_LIMIT));
        int off = Math.max(0, offset);
        StringBuilder where = new StringBuilder("where job_id = :job");
        if (kind != null && !kind.isBlank()) where.append(" and kind = :kind");
        if (query != null && !query.isBlank()) where.append(" and lower(qualified_name) like :q escape '\\'");

        var count = jdbc.sql("select count(*) from code_symbol " + where).param("job", jobId);
        var select = jdbc.sql("""
                select path, kind, subtype, simple_name, qualified_name, signature, parent_qualified_name, modifiers,
                       start_line, end_line, extraction_method, parser_version, commit_hash
                from code_symbol %s order by path, start_line, qualified_name limit :lim offset :off""".formatted(where))
                .param("job", jobId).param("lim", lim).param("off", off);
        if (kind != null && !kind.isBlank()) {
            count = count.param("kind", kind.toUpperCase());
            select = select.param("kind", kind.toUpperCase());
        }
        if (query != null && !query.isBlank()) {
            String pattern = SqlLike.contains(query.toLowerCase());
            count = count.param("q", pattern);
            select = select.param("q", pattern);
        }
        long total = count.query(Long.class).single();
        List<SymbolView> items = select.query((rs, i) -> new SymbolView(rs.getString("path"), rs.getString("kind"),
                rs.getString("subtype"), rs.getString("simple_name"), rs.getString("qualified_name"),
                rs.getString("signature"), rs.getString("parent_qualified_name"), rs.getString("modifiers"),
                rs.getInt("start_line"), rs.getInt("end_line"), rs.getString("extraction_method"),
                rs.getString("parser_version"), rs.getString("commit_hash"))).list();
        return new Page<>(new ArrayList<>(items), lim, off, total);
    }

    public Page<DiagnosticView> diagnostics(UUID jobId, int limit, int offset) {
        int lim = Math.max(1, Math.min(limit, MAX_LIMIT));
        int off = Math.max(0, offset);
        long total = jdbc.sql("select count(*) from parse_diagnostic where job_id = :j").param("j", jobId)
                .query(Long.class).single();
        List<DiagnosticView> items = jdbc.sql("""
                select path, severity, code, message, line from parse_diagnostic where job_id = :j
                order by path, line, id limit :lim offset :off""")
                .param("j", jobId).param("lim", lim).param("off", off)
                .query((rs, i) -> new DiagnosticView(rs.getString("path"), rs.getString("severity"),
                        rs.getString("code"), rs.getString("message"), rs.getInt("line"))).list();
        return new Page<>(items, lim, off, total);
    }
}
