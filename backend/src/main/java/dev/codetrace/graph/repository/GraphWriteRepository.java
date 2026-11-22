package dev.codetrace.graph.repository;

import dev.codetrace.graph.engine.GraphBuilder;
import dev.codetrace.graph.model.GraphModel.Edge;
import dev.codetrace.graph.model.GraphModel.Node;
import dev.codetrace.graph.model.GraphModel.Result;
import dev.codetrace.graph.model.Snapshot;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one snapshot's nodes and edges and moves it to VALIDATING in a single transaction: either the whole graph is
 * stored or none of it is. The snapshot stays invisible (not PUBLISHED) until validation succeeds.
 */
@Repository
public class GraphWriteRepository {
    private static final int BATCH = 1000;

    private final JdbcTemplate jdbc;

    public GraphWriteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void write(Snapshot snapshot, Result graph) {
        jdbc.batchUpdate("""
                insert into code_node (id, snapshot_id, repository_id, commit_hash, node_type, qualified_name, signature,
                    simple_name, subtype, path, start_line, end_line, modifiers, extraction_method, parser_version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", graph.nodes(), BATCH,
                (PreparedStatement ps, Node n) -> {
                    ps.setObject(1, n.id());
                    ps.setObject(2, snapshot.id());
                    ps.setObject(3, snapshot.repositoryId());
                    ps.setString(4, snapshot.commitHash());
                    ps.setString(5, n.type().name());
                    ps.setString(6, n.qualifiedName());
                    ps.setString(7, n.signature());
                    ps.setString(8, n.simpleName());
                    ps.setString(9, n.subtype());
                    ps.setString(10, n.path());
                    ps.setInt(11, n.startLine());
                    ps.setInt(12, n.endLine());
                    ps.setString(13, n.modifiers());
                    ps.setString(14, JavaSymbolExtractor.EXTRACTION_METHOD);
                    ps.setString(15, snapshot.parserVersion());
                });

        List<Edge> edges = graph.edges();
        jdbc.batchUpdate("""
                insert into dependency_edge (snapshot_id, repository_id, commit_hash, src_node_id, dst_node_id,
                    edge_type, dst_name, resolution_state, confidence, evidence_kind, reason, alternative_group,
                    evidence_path, evidence_line, occurrences, extraction_method, resolver_version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", edges, BATCH,
                (PreparedStatement ps, Edge e) -> bindEdge(ps, snapshot, e));

        jdbc.update("update graph_snapshot set status = 'VALIDATING', updated_at = now() where id = ? and status = 'BUILDING'",
                snapshot.id());
    }

    /**
     * Refreshes the planner statistics of the graph tables after a snapshot has been written. Until they are refreshed,
     * PostgreSQL estimates a single row for a snapshot it has never sampled and can choose plans that scan the whole
     * snapshot once per row.
     */
    public void refreshStatistics() {
        jdbc.execute("analyze code_node, dependency_edge");
    }

    private static void bindEdge(PreparedStatement ps, Snapshot s, Edge e) throws SQLException {
        ps.setObject(1, s.id());
        ps.setObject(2, s.repositoryId());
        ps.setString(3, s.commitHash());
        ps.setObject(4, e.srcId());
        if (e.dstId() == null) ps.setNull(5, Types.OTHER); else ps.setObject(5, e.dstId());
        ps.setString(6, e.type().name());
        ps.setString(7, e.dstName() == null ? "" : e.dstName());
        ps.setString(8, e.resolution().name());
        ps.setBigDecimal(9, BigDecimal.valueOf(e.confidence()).setScale(3, RoundingMode.HALF_UP));
        ps.setString(10, e.evidenceKind());
        ps.setString(11, e.reason() == null ? "" : e.reason());
        ps.setString(12, e.alternativeGroup() == null ? "" : e.alternativeGroup());
        ps.setString(13, e.evidencePath() == null ? "" : e.evidencePath());
        ps.setInt(14, e.evidenceLine());
        ps.setInt(15, e.occurrences());
        ps.setString(16, JavaSymbolExtractor.EXTRACTION_METHOD);
        ps.setString(17, GraphBuilder.VERSION);
    }
}
