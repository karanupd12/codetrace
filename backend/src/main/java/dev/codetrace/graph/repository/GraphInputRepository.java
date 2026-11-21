package dev.codetrace.graph.repository;

import dev.codetrace.graph.model.GraphModel.FileFact;
import dev.codetrace.graph.model.GraphModel.ImportFact;
import dev.codetrace.graph.model.GraphModel.Input;
import dev.codetrace.graph.model.GraphModel.RefFact;
import dev.codetrace.graph.model.GraphModel.SymbolFact;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reads the facts an INDEX job stored: files, symbols, imports and unresolved references. */
@Component
public class GraphInputRepository {
    private final JdbcClient jdbc;

    public GraphInputRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Input load(UUID indexJobId) {
        var files = jdbc.sql("""
                select path, package_name, status from indexed_file where job_id = :j and status in ('PARSED', 'PARTIAL')""")
                .param("j", indexJobId)
                .query((rs, i) -> new FileFact(rs.getString("path"), rs.getString("package_name"), rs.getString("status")))
                .list();
        var symbols = jdbc.sql("""
                select path, kind, subtype, simple_name, qualified_name, signature, parent_qualified_name, modifiers,
                       start_line, end_line from code_symbol where job_id = :j""")
                .param("j", indexJobId)
                .query((rs, i) -> new SymbolFact(rs.getString("path"), rs.getString("kind"), rs.getString("subtype"),
                        rs.getString("simple_name"), rs.getString("qualified_name"), rs.getString("signature"),
                        rs.getString("parent_qualified_name"), rs.getString("modifiers"), rs.getInt("start_line"),
                        rs.getInt("end_line")))
                .list();
        var imports = jdbc.sql("""
                select path, imported_name, is_static, is_wildcard, line from code_import where job_id = :j""")
                .param("j", indexJobId)
                .query((rs, i) -> new ImportFact(rs.getString("path"), rs.getString("imported_name"),
                        rs.getBoolean("is_static"), rs.getBoolean("is_wildcard"), rs.getInt("line")))
                .list();
        var refs = jdbc.sql("""
                select path, owner_qualified_name, kind, name, arg_count, receiver_kind, receiver_type, line
                from code_reference where job_id = :j""")
                .param("j", indexJobId)
                .query((rs, i) -> new RefFact(rs.getString("path"), rs.getString("owner_qualified_name"),
                        rs.getString("kind"), rs.getString("name"), rs.getInt("arg_count"),
                        rs.getString("receiver_kind"), rs.getString("receiver_type"), rs.getInt("line")))
                .list();
        return new Input(files, symbols, imports, refs);
    }
}
