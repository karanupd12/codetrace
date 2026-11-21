package dev.codetrace.impact;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only queries on a published snapshot that impact analysis needs beyond the generic graph queries. */
@Repository
public class ImpactDao {
    private final JdbcClient jdbc;

    public ImpactDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** For each target, how many distinct symbols have a resolved or ambiguous dependency edge of the given types into it. */
    public Map<UUID, Integer> directDependents(UUID snapshotId, Collection<UUID> targets, Set<String> edgeTypes) {
        Map<UUID, Integer> out = new HashMap<>();
        List<UUID> all = new ArrayList<>(targets);
        for (int i = 0; i < all.size(); i += 1000) {
            jdbc.sql("""
                    select dst_node_id, count(distinct src_node_id) as n
                      from dependency_edge
                     where snapshot_id = :s and dst_node_id in (:ids) and edge_type in (:types)
                       and resolution_state in ('RESOLVED', 'AMBIGUOUS')
                     group by dst_node_id""")
                    .param("s", snapshotId).param("ids", all.subList(i, Math.min(all.size(), i + 1000)))
                    .param("types", edgeTypes)
                    .query((rs, n) -> Map.entry(rs.getObject("dst_node_id", UUID.class), rs.getInt("n")))
                    .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        }
        return out;
    }

    public record UnresolvedCaller(UUID nodeId, String qualifiedName, String path, int line, String methodName, int argCount) {
    }

    /**
     * Calls whose receiver type the resolver could not determine and whose method name is one of {@code methodNames}.
     * The resolver records such a target as {@code ?#name/argCount}. They are not proven dependents, only candidates
     * the report should mention; the caller filters them by argument count.
     */
    public List<UnresolvedCaller> unknownReceiverCallers(UUID snapshotId, Collection<String> methodNames, int limit) {
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
                    return new UnresolvedCaller(rs.getObject("src_node_id", UUID.class), rs.getString("qualified_name"),
                            rs.getString("evidence_path"), rs.getInt("evidence_line"), target[0], argc);
                })
                .list();
    }
}
