package dev.codetrace.graph;

import dev.codetrace.system.Page;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL over one snapshot. Every query is scoped by {@code snapshot_id} and bounded by an explicit limit; callers
 * decide which snapshots may be read (only PUBLISHED ones).
 */
@Repository
public class GraphQueryDao {
    public record NodeView(UUID id, String nodeType, String qualifiedName, String signature, String simpleName,
                           String subtype, String path, int startLine, int endLine, String modifiers,
                           String extractionMethod, String parserVersion) {
    }

    /** An edge with its full provenance. {@code dstId} is null for UNRESOLVED edges; {@code dstName} then says what was sought. */
    public record EdgeView(long id, UUID srcId, String srcName, UUID dstId, String dstName, String edgeType,
                           String resolution, double confidence, String evidenceKind, String reason,
                           String alternativeGroup, String evidencePath, int evidenceLine, int occurrences,
                           String extractionMethod, String resolverVersion) {
    }

    public record Degree(String edgeType, String resolution, long count) {
    }

    public enum Direction { OUT, IN, BOTH }

    private static final String EDGE_SELECT = """
            select e.id, e.src_node_id, s.qualified_name as src_name, e.dst_node_id,
                   coalesce(d.qualified_name, e.dst_name) as dst_name, e.edge_type, e.resolution_state, e.confidence,
                   e.evidence_kind, e.reason, e.alternative_group, e.evidence_path, e.evidence_line, e.occurrences,
                   e.extraction_method, e.resolver_version
            from dependency_edge e
              join code_node s on s.snapshot_id = e.snapshot_id and s.id = e.src_node_id
              left join code_node d on d.snapshot_id = e.snapshot_id and d.id = e.dst_node_id""";

    private static final String NODE_COLS = """
            id, node_type, qualified_name, signature, simple_name, subtype, path, start_line, end_line, modifiers,
            extraction_method, parser_version""";

    private final JdbcClient jdbc;

    public GraphQueryDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- nodes ------------------------------------------------------------------------------------------------------

    public Page<NodeView> searchNodes(UUID snapshotId, String type, String q, String path, int limit, int offset) {
        StringBuilder where = new StringBuilder("where snapshot_id = :s");
        if (type != null) where.append(" and node_type = :t");
        if (q != null) where.append(" and lower(qualified_name) like :q escape '\\'");
        if (path != null) where.append(" and path = :p");

        var count = jdbc.sql("select count(*) from code_node " + where).param("s", snapshotId);
        var select = jdbc.sql("select " + NODE_COLS + " from code_node " + where
                + " order by node_type, qualified_name, signature limit :lim offset :off")
                .param("s", snapshotId).param("lim", limit).param("off", offset);
        if (type != null) { count = count.param("t", type); select = select.param("t", type); }
        if (q != null) {
            String pattern = "%" + escapeLike(q.toLowerCase()) + "%";
            count = count.param("q", pattern);
            select = select.param("q", pattern);
        }
        if (path != null) { count = count.param("p", path); select = select.param("p", path); }
        return new Page<>(select.query(GraphQueryDao::node).list(), limit, offset, count.query(Long.class).single());
    }

    public Optional<NodeView> findNode(UUID snapshotId, UUID nodeId) {
        return jdbc.sql("select " + NODE_COLS + " from code_node where snapshot_id = :s and id = :n")
                .param("s", snapshotId).param("n", nodeId).query(GraphQueryDao::node).optional();
    }

    public List<NodeView> nodesByIds(UUID snapshotId, Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        List<NodeView> out = new ArrayList<>();
        for (List<UUID> chunk : chunks(ids, 1000)) {
            out.addAll(jdbc.sql("select " + NODE_COLS + " from code_node where snapshot_id = :s and id in (:ids) order by qualified_name")
                    .param("s", snapshotId).param("ids", chunk).query(GraphQueryDao::node).list());
        }
        return out;
    }

    /** Edge counts leaving and entering a node, by type and resolution state. */
    public List<Degree> degrees(UUID snapshotId, UUID nodeId, Direction direction) {
        String col = direction == Direction.OUT ? "src_node_id" : "dst_node_id";
        return jdbc.sql("select edge_type, resolution_state, count(*) as n from dependency_edge where snapshot_id = :s and "
                + col + " = :n group by edge_type, resolution_state order by edge_type, resolution_state")
                .param("s", snapshotId).param("n", nodeId)
                .query((rs, i) -> new Degree(rs.getString("edge_type"), rs.getString("resolution_state"), rs.getLong("n")))
                .list();
    }

    // ---- edges ------------------------------------------------------------------------------------------------------

    public Page<EdgeView> edgesOf(UUID snapshotId, UUID nodeId, Direction dir, Set<String> types, Set<String> states,
                                  int limit, int offset) {
        String touch = switch (dir) {
            case OUT -> "e.src_node_id = :n";
            case IN -> "e.dst_node_id = :n";
            case BOTH -> "(e.src_node_id = :n or e.dst_node_id = :n)";
        };
        String where = " where e.snapshot_id = :s and " + touch + " and e.edge_type in (:types) and e.resolution_state in (:states)";
        long total = jdbc.sql("select count(*) from dependency_edge e" + where).param("s", snapshotId).param("n", nodeId)
                .param("types", types).param("states", states).query(Long.class).single();
        List<EdgeView> items = jdbc.sql(EDGE_SELECT + where + " order by e.edge_type, e.id limit :lim offset :off")
                .param("s", snapshotId).param("n", nodeId).param("types", types).param("states", states)
                .param("lim", limit).param("off", offset).query(GraphQueryDao::edge).list();
        return new Page<>(items, limit, offset, total);
    }

    /**
     * Edges touching any of {@code ids}, for traversal. OUT follows edges from the ids and includes UNRESOLVED ones; IN
     * follows edges into the ids; BOTH is the union. At most {@code limit} rows, ordered by edge id for determinism.
     */
    public List<EdgeView> touching(UUID snapshotId, Collection<UUID> ids, Direction dir, Set<String> types,
                                   Set<String> states, boolean resolvedTargetsOnly, int limit) {
        if (ids.isEmpty() || limit <= 0) return List.of();
        String touch = switch (dir) {
            case OUT -> "e.src_node_id in (:ids)";
            case IN -> "e.dst_node_id in (:ids)";
            case BOTH -> "(e.src_node_id in (:ids) or e.dst_node_id in (:ids))";
        };
        String where = " where e.snapshot_id = :s and " + touch + " and e.edge_type in (:types) and e.resolution_state in (:states)"
                + (resolvedTargetsOnly ? " and e.dst_node_id is not null" : "");
        List<EdgeView> out = new ArrayList<>();
        for (List<UUID> chunk : chunks(ids, 1000)) {
            int remaining = limit - out.size();
            if (remaining <= 0) break;
            out.addAll(jdbc.sql(EDGE_SELECT + where + " order by e.id limit :lim")
                    .param("s", snapshotId).param("ids", chunk).param("types", types).param("states", states)
                    .param("lim", remaining).query(GraphQueryDao::edge).list());
        }
        return out;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static <T> List<List<T>> chunks(Collection<T> items, int size) {
        List<T> all = new ArrayList<>(items);
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) out.add(all.subList(i, Math.min(all.size(), i + size)));
        return out;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static NodeView node(ResultSet rs, int i) throws SQLException {
        return new NodeView(rs.getObject("id", UUID.class), rs.getString("node_type"), rs.getString("qualified_name"),
                rs.getString("signature"), rs.getString("simple_name"), rs.getString("subtype"), rs.getString("path"),
                rs.getInt("start_line"), rs.getInt("end_line"), rs.getString("modifiers"),
                rs.getString("extraction_method"), rs.getString("parser_version"));
    }

    private static EdgeView edge(ResultSet rs, int i) throws SQLException {
        return new EdgeView(rs.getLong("id"), rs.getObject("src_node_id", UUID.class), rs.getString("src_name"),
                rs.getObject("dst_node_id", UUID.class), rs.getString("dst_name"), rs.getString("edge_type"),
                rs.getString("resolution_state"), rs.getBigDecimal("confidence").doubleValue(),
                rs.getString("evidence_kind"), rs.getString("reason"), rs.getString("alternative_group"),
                rs.getString("evidence_path"), rs.getInt("evidence_line"), rs.getInt("occurrences"),
                rs.getString("extraction_method"), rs.getString("resolver_version"));
    }
}
