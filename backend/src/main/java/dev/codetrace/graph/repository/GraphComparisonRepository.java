package dev.codetrace.graph.repository;

import dev.codetrace.common.web.Page;
import dev.codetrace.graph.model.ChangeCount;
import dev.codetrace.graph.model.EdgeChange;
import dev.codetrace.graph.model.NodeChange;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Differences between two snapshots. Node ids are per snapshot, so rows are matched by identity instead: a node by
 * (type, path, qualified name, signature), an edge by both endpoint identities, its type and its resolution state (an
 * unresolved target by its name).
 */
@Repository
public class GraphComparisonRepository {
    private static final String NODE_DIFF = """
            with b as (select id, node_type, path, qualified_name, signature, subtype, modifiers
                         from code_node where snapshot_id = :base),
                 h as (select id, node_type, path, qualified_name, signature, subtype, modifiers
                         from code_node where snapshot_id = :head),
                 diff as (
                   select case when b.id is null then 'ADDED' when h.id is null then 'REMOVED' else 'CHANGED' end as change,
                          coalesce(h.node_type, b.node_type) as node_type, coalesce(h.path, b.path) as path,
                          coalesce(h.qualified_name, b.qualified_name) as qualified_name,
                          coalesce(h.signature, b.signature) as signature,
                          b.id as base_node_id, h.id as head_node_id, b.subtype as base_subtype, h.subtype as head_subtype,
                          b.modifiers as base_modifiers, h.modifiers as head_modifiers
                     from b full join h on b.node_type = h.node_type and b.path = h.path
                                       and b.qualified_name = h.qualified_name and b.signature = h.signature
                    where b.id is null or h.id is null or b.subtype <> h.subtype or b.modifiers <> h.modifiers)
            """;

    private static final String EDGE_KEYS = """
            select e.edge_type, e.resolution_state, e.confidence, e.evidence_path, e.evidence_line,
                   s.node_type as src_type, s.path as src_path, s.qualified_name as src_name, s.signature as src_signature,
                   coalesce(d.node_type, '') as dst_type, coalesce(d.path, '') as dst_path,
                   coalesce(d.qualified_name, e.dst_name) as dst_name, coalesce(d.signature, '') as dst_signature
              from dependency_edge e
              join code_node s on s.id = e.src_node_id
              left join code_node d on d.id = e.dst_node_id
             where e.snapshot_id = %s""";

    private static final String EDGE_DIFF = """
            with b as (%s), h as (%s),
                 diff as (
                   select case when b.edge_type is null then 'ADDED' else 'REMOVED' end as change,
                          coalesce(h.edge_type, b.edge_type) as edge_type,
                          coalesce(h.resolution_state, b.resolution_state) as resolution_state,
                          coalesce(h.confidence, b.confidence) as confidence,
                          coalesce(h.src_type, b.src_type) as src_type, coalesce(h.src_name, b.src_name) as src_name,
                          coalesce(h.src_signature, b.src_signature) as src_signature,
                          coalesce(h.src_path, b.src_path) as src_path,
                          coalesce(h.dst_type, b.dst_type) as dst_type, coalesce(h.dst_name, b.dst_name) as dst_name,
                          coalesce(h.dst_signature, b.dst_signature) as dst_signature,
                          coalesce(h.evidence_path, b.evidence_path) as evidence_path,
                          coalesce(h.evidence_line, b.evidence_line) as evidence_line
                     from b full join h
                       on b.edge_type = h.edge_type and b.resolution_state = h.resolution_state
                      and b.src_type = h.src_type and b.src_path = h.src_path and b.src_name = h.src_name
                      and b.src_signature = h.src_signature and b.dst_type = h.dst_type and b.dst_path = h.dst_path
                      and b.dst_name = h.dst_name and b.dst_signature = h.dst_signature
                    where b.edge_type is null or h.edge_type is null)
            """.formatted(EDGE_KEYS.formatted(":base"), EDGE_KEYS.formatted(":head"));

    private final JdbcClient jdbc;

    public GraphComparisonRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Changed node counts per change kind and node type. */
    public List<ChangeCount> nodeCounts(UUID base, UUID head) {
        return jdbc.sql(NODE_DIFF + "select change, node_type as kind, count(*) as n from diff group by 1, 2 order by 1, 2")
                .param("base", base).param("head", head)
                .query((rs, i) -> new ChangeCount(rs.getString("change"), rs.getString("kind"), rs.getLong("n")))
                .list();
    }

    /** Changed edge counts per change kind and edge type. */
    public List<ChangeCount> edgeCounts(UUID base, UUID head) {
        return jdbc.sql(EDGE_DIFF + "select change, edge_type as kind, count(*) as n from diff group by 1, 2 order by 1, 2")
                .param("base", base).param("head", head)
                .query((rs, i) -> new ChangeCount(rs.getString("change"), rs.getString("kind"), rs.getLong("n")))
                .list();
    }

    public Page<NodeChange> nodes(UUID base, UUID head, String change, String type, int limit, int offset) {
        String where = " where (cast(:c as text) is null or change = :c) and (cast(:t as text) is null or node_type = :t)";
        long total = jdbc.sql(NODE_DIFF + "select count(*) from diff" + where)
                .param("base", base).param("head", head).param("c", change).param("t", type)
                .query(Long.class).single();
        List<NodeChange> items = jdbc.sql(NODE_DIFF + "select * from diff" + where
                        + " order by change, node_type, qualified_name, signature, path limit :lim offset :off")
                .param("base", base).param("head", head).param("c", change).param("t", type)
                .param("lim", limit).param("off", offset)
                .query((rs, i) -> new NodeChange(rs.getString("change"), rs.getString("node_type"),
                        rs.getString("qualified_name"), rs.getString("signature"), rs.getString("path"),
                        rs.getObject("base_node_id", UUID.class), rs.getObject("head_node_id", UUID.class),
                        rs.getString("base_subtype"), rs.getString("head_subtype"), rs.getString("base_modifiers"),
                        rs.getString("head_modifiers")))
                .list();
        return new Page<>(items, limit, offset, total);
    }

    public Page<EdgeChange> edges(UUID base, UUID head, String change, Collection<String> types, Collection<String> states,
                                  int limit, int offset) {
        String where = " where (cast(:c as text) is null or change = :c) and edge_type in (:types)"
                + " and resolution_state in (:states)";
        long total = jdbc.sql(EDGE_DIFF + "select count(*) from diff" + where)
                .param("base", base).param("head", head).param("c", change).param("types", types)
                .param("states", states)
                .query(Long.class).single();
        List<EdgeChange> items = jdbc.sql(EDGE_DIFF + "select * from diff" + where
                        + " order by change, edge_type, src_name, src_signature, dst_name, dst_signature, resolution_state"
                        + " limit :lim offset :off")
                .param("base", base).param("head", head).param("c", change).param("types", types)
                .param("states", states).param("lim", limit).param("off", offset)
                .query((rs, i) -> new EdgeChange(rs.getString("change"), rs.getString("edge_type"),
                        rs.getString("resolution_state"), rs.getBigDecimal("confidence").doubleValue(),
                        rs.getString("src_type"), rs.getString("src_name"), rs.getString("src_signature"),
                        rs.getString("dst_type"), rs.getString("dst_name"), rs.getString("dst_signature"),
                        rs.getString("evidence_path"), rs.getInt("evidence_line")))
                .list();
        return new Page<>(items, limit, offset, total);
    }
}
