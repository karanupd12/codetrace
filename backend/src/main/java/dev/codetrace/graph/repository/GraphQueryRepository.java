package dev.codetrace.graph.repository;

import dev.codetrace.graph.model.Degree;
import dev.codetrace.graph.model.Direction;
import dev.codetrace.graph.model.EdgeView;
import dev.codetrace.graph.model.NodeView;
import dev.codetrace.graph.model.UnresolvedCall;
import dev.codetrace.common.util.Batches;
import dev.codetrace.common.util.SqlLike;
import dev.codetrace.common.web.Page;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
public class GraphQueryRepository {
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

    public GraphQueryRepository(JdbcClient jdbc) {
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
            String pattern = SqlLike.contains(q.toLowerCase());
            count = count.param("q", pattern);
            select = select.param("q", pattern);
        }
        if (path != null) { count = count.param("p", path); select = select.param("p", path); }
        return new Page<>(select.query(GraphQueryRepository::node).list(), limit, offset, count.query(Long.class).single());
    }

    public Optional<NodeView> findNode(UUID snapshotId, UUID nodeId) {
        return jdbc.sql("select " + NODE_COLS + " from code_node where snapshot_id = :s and id = :n")
                .param("s", snapshotId).param("n", nodeId).query(GraphQueryRepository::node).optional();
    }

    public List<NodeView> nodesByIds(UUID snapshotId, Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        List<NodeView> out = new ArrayList<>();
        for (List<UUID> chunk : Batches.partition(ids)) {
            out.addAll(jdbc.sql("select " + NODE_COLS + " from code_node where snapshot_id = :s and id in (:ids) order by qualified_name")
                    .param("s", snapshotId).param("ids", chunk).query(GraphQueryRepository::node).list());
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
                .param("lim", limit).param("off", offset).query(GraphQueryRepository::edge).list();
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
        for (List<UUID> chunk : Batches.partition(ids)) {
            int remaining = limit - out.size();
            if (remaining <= 0) break;
            out.addAll(jdbc.sql(EDGE_SELECT + where + " order by e.id limit :lim")
                    .param("s", snapshotId).param("ids", chunk).param("types", types).param("states", states)
                    .param("lim", remaining).query(GraphQueryRepository::edge).list());
        }
        return out;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    // ---- declarations and dependents (used by impact analysis) ------------------------------------------------------

    /** Types, methods and fields declared in the given files, in file and line order. */
    public List<NodeView> declarationsIn(UUID snapshotId, Collection<String> paths) {
        List<NodeView> out = new ArrayList<>();
        for (List<String> chunk : Batches.partition(paths)) {
            out.addAll(jdbc.sql("select " + NODE_COLS + " from code_node where snapshot_id = :s and path in (:paths)"
                            + " and node_type in ('TYPE', 'METHOD', 'FIELD')"
                            + " order by path, start_line, end_line, qualified_name, signature")
                    .param("s", snapshotId).param("paths", chunk).query(GraphQueryRepository::node).list());
        }
        return out;
    }

    /** For each target, how many distinct nodes have a resolved or ambiguous edge of the given types into it. */
    public Map<UUID, Integer> dependentCounts(UUID snapshotId, Collection<UUID> targets, Set<String> edgeTypes) {
        Map<UUID, Integer> out = new HashMap<>();
        for (List<UUID> chunk : Batches.partition(targets)) {
            jdbc.sql("""
                    select dst_node_id, count(distinct src_node_id) as n
                      from dependency_edge
                     where snapshot_id = :s and dst_node_id in (:ids) and edge_type in (:types)
                       and resolution_state in ('RESOLVED', 'AMBIGUOUS')
                     group by dst_node_id""")
                    .param("s", snapshotId).param("ids", chunk).param("types", edgeTypes)
                    .query((rs, n) -> Map.entry(rs.getObject("dst_node_id", UUID.class), rs.getInt("n")))
                    .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        }
        return out;
    }

    /**
     * Calls whose receiver type could not be determined and whose method name is one of {@code methodNames}. The
     * resolver records such a target as {@code ?#name/argCount}.
     */
    public List<UnresolvedCall> unknownReceiverCalls(UUID snapshotId, Collection<String> methodNames, int limit) {
        if (methodNames.isEmpty() || limit <= 0) return List.of();
        List<String> sought = methodNames.stream().map(n -> "?#" + n).toList();
        return jdbc.sql("""
                select e.src_node_id, n.qualified_name, e.evidence_path, e.evidence_line, e.dst_name
                  from dependency_edge e
                  join code_node n on n.snapshot_id = e.snapshot_id and n.id = e.src_node_id
                 where e.snapshot_id = :s and e.edge_type = 'CALLS' and e.resolution_state = 'UNRESOLVED'
                   and e.reason = 'RECEIVER_TYPE_UNKNOWN' and split_part(e.dst_name, '/', 1) in (:names)
                 order by e.evidence_path, e.evidence_line, e.id
                 limit :lim""")
                .param("s", snapshotId).param("names", sought).param("lim", limit)
                .query((rs, i) -> {
                    String[] target = rs.getString("dst_name").substring(2).split("/", 2); // name, argCount
                    int argc = target.length > 1 ? Integer.parseInt(target[1]) : -1;
                    return new UnresolvedCall(rs.getObject("src_node_id", UUID.class), rs.getString("qualified_name"),
                            rs.getString("evidence_path"), rs.getInt("evidence_line"), target[0], argc);
                })
                .list();
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
