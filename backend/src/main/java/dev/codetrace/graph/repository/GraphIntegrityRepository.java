package dev.codetrace.graph.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Checks a staged snapshot's invariants with SQL before it may be published. Foreign keys and CHECK constraints already
 * make dangling edges and malformed edges impossible; these queries verify the properties constraints cannot express.
 * Any violation fails the snapshot: it is never published "mostly valid".
 */
@Repository
public class GraphIntegrityRepository {
    private final JdbcClient jdbc;

    public GraphIntegrityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return human-readable violations; empty when the snapshot is valid. */
    public List<String> validate(UUID snapshotId, int expectedNodes, int expectedEdges) {
        List<String> violations = new ArrayList<>();
        check(violations, "stored node count differs from the built graph",
                "select case when count(*) = :n then 0 else 1 end from code_node where snapshot_id = :s", snapshotId,
                expectedNodes, expectedEdges);
        check(violations, "stored edge count differs from the built graph",
                "select case when count(*) = :e then 0 else 1 end from dependency_edge where snapshot_id = :s", snapshotId,
                expectedNodes, expectedEdges);
        // Container counts come from one grouped pass over nodes and CONTAINS edges instead of a per-node subquery,
        // so the cost stays linear even when the planner has no statistics for a freshly written snapshot.
        check(violations, "declarations without exactly one container", """
                select count(*) from (
                    select id from (
                        select n.id, 1 as declaration, 0 as containers from code_node n
                         where n.snapshot_id = :s and n.node_type in ('TYPE', 'METHOD', 'FIELD')
                        union all
                        select e.dst_node_id, 0, 1 from dependency_edge e
                         where e.snapshot_id = :s and e.edge_type = 'CONTAINS'
                    ) x group by id having max(declaration) = 1 and sum(containers) <> 1) v""",
                snapshotId, expectedNodes, expectedEdges);
        check(violations, "files with more than one container", """
                select count(*) from (
                    select id from (
                        select n.id, 1 as file, 0 as containers from code_node n
                         where n.snapshot_id = :s and n.node_type = 'FILE'
                        union all
                        select e.dst_node_id, 0, 1 from dependency_edge e
                         where e.snapshot_id = :s and e.edge_type = 'CONTAINS'
                    ) x group by id having max(file) = 1 and sum(containers) > 1) v""",
                snapshotId, expectedNodes, expectedEdges);
        check(violations, "CONTAINS edges that are not RESOLVED", """
                select count(*) from dependency_edge where snapshot_id = :s and edge_type = 'CONTAINS'
                  and resolution_state <> 'RESOLVED'""", snapshotId, expectedNodes, expectedEdges);
        check(violations, "ambiguous alternative groups with fewer than two candidates", """
                select count(*) from (select alternative_group from dependency_edge
                    where snapshot_id = :s and resolution_state = 'AMBIGUOUS'
                    group by alternative_group having count(*) < 2) g""", snapshotId, expectedNodes, expectedEdges);
        // Endpoints are joined by node id alone: ids are unique across snapshots and the composite foreign keys already
        // keep both endpoints in the edge's snapshot. Leaving snapshot_id off the node side lets the planner use the
        // primary key per edge or a hash join, never a scan of the whole snapshot for every edge.
        check(violations, "edges whose endpoint node types do not fit the edge type", """
                select count(*) from dependency_edge e
                  join code_node s on s.id = e.src_node_id
                  left join code_node d on d.id = e.dst_node_id
                where e.snapshot_id = :s and not (
                     (e.edge_type = 'CONTAINS'   and ((s.node_type = 'PACKAGE' and d.node_type = 'FILE')
                                                   or (s.node_type = 'FILE'    and d.node_type = 'TYPE')
                                                   or (s.node_type = 'TYPE'    and d.node_type in ('TYPE', 'METHOD', 'FIELD'))))
                  or (e.edge_type in ('EXTENDS', 'IMPLEMENTS') and s.node_type = 'TYPE' and (d.id is null or d.node_type = 'TYPE'))
                  or (e.edge_type = 'IMPORTS'    and s.node_type = 'FILE' and (d.id is null or d.node_type in ('TYPE', 'PACKAGE')))
                  or (e.edge_type = 'REFERENCES' and s.node_type in ('TYPE', 'METHOD', 'FIELD') and (d.id is null or d.node_type = 'TYPE'))
                  or (e.edge_type = 'CALLS'      and s.node_type in ('TYPE', 'METHOD', 'FIELD') and (d.id is null or d.node_type = 'METHOD'))
                  or (e.edge_type = 'TESTS'      and s.node_type = 'TYPE' and d.node_type = 'TYPE'))""",
                snapshotId, expectedNodes, expectedEdges);
        return violations;
    }

    private void check(List<String> violations, String description, String sql, UUID snapshotId, int nodes, int edges) {
        var spec = jdbc.sql(sql).param("s", snapshotId);
        if (sql.contains(":n")) spec = spec.param("n", nodes);
        if (sql.contains(":e")) spec = spec.param("e", edges);
        Long count = spec.query(Long.class).single();
        if (count != null && count > 0) violations.add(description + " (" + count + ")");
    }
}
