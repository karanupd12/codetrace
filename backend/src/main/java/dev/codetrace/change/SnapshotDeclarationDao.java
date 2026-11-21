package dev.codetrace.change;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the declarations (types, methods, fields) a published snapshot holds for given files. */
@Repository
public class SnapshotDeclarationDao {
    private final JdbcClient jdbc;

    public SnapshotDeclarationDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, List<SymbolRef>> byPath(UUID snapshotId, Collection<String> paths) {
        Map<String, List<SymbolRef>> out = new LinkedHashMap<>();
        List<String> all = new ArrayList<>(paths);
        for (int i = 0; i < all.size(); i += 1000) {
            List<String> chunk = all.subList(i, Math.min(all.size(), i + 1000));
            jdbc.sql("""
                    select id, node_type, qualified_name, signature, path, start_line, end_line, modifiers
                      from code_node
                     where snapshot_id = :s and path in (:paths) and node_type in ('TYPE', 'METHOD', 'FIELD')
                     order by path, start_line, end_line, qualified_name, signature""")
                    .param("s", snapshotId).param("paths", chunk)
                    .query((rs, n) -> new SymbolRef(rs.getObject("id", UUID.class), rs.getString("node_type"),
                            rs.getString("qualified_name"), rs.getString("signature"), rs.getString("path"),
                            rs.getInt("start_line"), rs.getInt("end_line"), rs.getString("modifiers")))
                    .list()
                    .forEach(s -> out.computeIfAbsent(s.path(), k -> new ArrayList<>()).add(s));
        }
        return out;
    }
}
