package dev.codetrace.graph.repository;

import dev.codetrace.graph.model.Snapshot;
import dev.codetrace.common.util.Texts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Snapshot lifecycle: REQUESTED -> BUILDING -> VALIDATING -> PUBLISHED, or FAILED. Only PUBLISHED snapshots are ever
 * readable through the query API, and the database refuses any change to one.
 */
@Repository
public class GraphSnapshotRepository {
    public record Created(Snapshot snapshot, boolean created) {
    }

    private static final String COLS = """
            id, repository_id, commit_hash, parser_version, builder_version, source_job_id, status, node_count,
            edge_count, resolved_edge_count, ambiguous_edge_count, unresolved_edge_count, failure_reason, created_at,
            published_at""";

    private final JdbcClient jdbc;

    public GraphSnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: the identity (repository, commit, parser version, builder version) always maps to one row. */
    public Created createOrGet(UUID repositoryId, String commitHash, String parserVersion, String builderVersion,
                               UUID sourceJobId) {
        int n = jdbc.sql("""
                insert into graph_snapshot (id, repository_id, commit_hash, parser_version, builder_version,
                    source_job_id, status)
                values (:id, :r, :c, :p, :b, :j, 'REQUESTED')
                on conflict on constraint uq_graph_snapshot_identity do nothing""")
                .param("id", UUID.randomUUID()).param("r", repositoryId).param("c", commitHash)
                .param("p", parserVersion).param("b", builderVersion).param("j", sourceJobId, Types.OTHER).update();
        Snapshot s = jdbc.sql("select " + COLS + " from graph_snapshot where repository_id = :r and commit_hash = :c"
                + " and parser_version = :p and builder_version = :b")
                .param("r", repositoryId).param("c", commitHash).param("p", parserVersion).param("b", builderVersion)
                .query(GraphSnapshotRepository::map).single();
        return new Created(s, n == 1);
    }

    public Optional<Snapshot> find(UUID id) {
        return jdbc.sql("select " + COLS + " from graph_snapshot where id = :id").param("id", id)
                .query(GraphSnapshotRepository::map).optional();
    }

    public List<Snapshot> listForRepository(UUID repositoryId, int limit) {
        return jdbc.sql("select " + COLS + " from graph_snapshot where repository_id = :r order by created_at desc limit :n")
                .param("r", repositoryId).param("n", limit).query(GraphSnapshotRepository::map).list();
    }

    public Optional<Snapshot> latestPublished(UUID repositoryId, String commitHash) {
        return jdbc.sql("select " + COLS + " from graph_snapshot where repository_id = :r and commit_hash = :c"
                + " and status = 'PUBLISHED' order by published_at desc limit 1")
                .param("r", repositoryId).param("c", commitHash).query(GraphSnapshotRepository::map).optional();
    }

    /** Prepares a (re)build: clears any earlier partial rows and marks BUILDING. False if already published. */
    public boolean begin(UUID id) {
        int moved = jdbc.sql("""
                update graph_snapshot set status = 'BUILDING', failure_reason = null, updated_at = now()
                where id = :id and status in ('REQUESTED', 'BUILDING', 'VALIDATING', 'FAILED')""")
                .param("id", id).update();
        if (moved == 0) return false;
        clearGraph(id);
        return true;
    }

    /** Back to REQUESTED so a failed snapshot can be queued again. No-op for published snapshots. */
    public void requeue(UUID id) {
        jdbc.sql("""
                update graph_snapshot set status = 'REQUESTED', failure_reason = null, updated_at = now()
                where id = :id and status = 'FAILED'""").param("id", id).update();
    }

    /** Marks an unpublished snapshot FAILED and discards its rows. A no-op for a PUBLISHED snapshot. */
    public void markFailed(UUID id, String reason) {
        int updated = jdbc.sql("""
                update graph_snapshot set status = 'FAILED', failure_reason = :why, node_count = null, edge_count = null,
                    resolved_edge_count = null, ambiguous_edge_count = null, unresolved_edge_count = null,
                    updated_at = now() where id = :id and status <> 'PUBLISHED'""")
                .param("id", id).param("why", Texts.truncate(reason, 500)).update();
        if (updated == 1) clearGraph(id); // status first: once FAILED the rows are no longer protected
    }

    /**
     * The single atomic step that makes a snapshot visible. Counts are taken from the tables in the same statement, so
     * the published numbers can never disagree with the published rows.
     */
    public boolean publish(UUID id) {
        return jdbc.sql("""
                update graph_snapshot s set status = 'PUBLISHED', published_at = now(), updated_at = now(),
                    node_count = (select count(*) from code_node where snapshot_id = s.id),
                    edge_count = (select count(*) from dependency_edge where snapshot_id = s.id),
                    resolved_edge_count = (select count(*) from dependency_edge where snapshot_id = s.id
                                           and resolution_state = 'RESOLVED'),
                    ambiguous_edge_count = (select count(*) from dependency_edge where snapshot_id = s.id
                                            and resolution_state = 'AMBIGUOUS'),
                    unresolved_edge_count = (select count(*) from dependency_edge where snapshot_id = s.id
                                             and resolution_state = 'UNRESOLVED')
                where s.id = :id and s.status = 'VALIDATING'""").param("id", id).update() == 1;
    }

    private void clearGraph(UUID id) {
        jdbc.sql("delete from dependency_edge where snapshot_id = :id").param("id", id).update();
        jdbc.sql("delete from code_node where snapshot_id = :id").param("id", id).update();
    }

    private static Snapshot map(ResultSet rs, int i) throws SQLException {
        return new Snapshot(rs.getObject("id", UUID.class), rs.getObject("repository_id", UUID.class),
                rs.getString("commit_hash"), rs.getString("parser_version"), rs.getString("builder_version"),
                rs.getObject("source_job_id", UUID.class), rs.getString("status"),
                rs.getObject("node_count", Integer.class), rs.getObject("edge_count", Integer.class),
                rs.getObject("resolved_edge_count", Integer.class), rs.getObject("ambiguous_edge_count", Integer.class),
                rs.getObject("unresolved_edge_count", Integer.class), rs.getString("failure_reason"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("published_at", OffsetDateTime.class));
    }

    /**
     * Deletes snapshots built by another builder version once a published snapshot of the current version exists for
     * the same commit, unless an impact report still refers to them. Their nodes and edges go with them.
     */
    public int deleteSuperseded(String currentBuilder, int limit) {
        return jdbc.sql("""
                delete from graph_snapshot where id in (
                  select s.id from graph_snapshot s
                   where s.builder_version <> :cur
                     and exists (select 1 from graph_snapshot n where n.repository_id = s.repository_id
                                   and n.commit_hash = s.commit_hash and n.builder_version = :cur and n.status = 'PUBLISHED')
                     and not exists (select 1 from impact_report r where r.base_snapshot_id = s.id or r.head_snapshot_id = s.id)
                   limit :lim)""")
                .param("cur", currentBuilder).param("lim", limit).update();
    }

    /** Deletes FAILED snapshots older than {@code days}. */
    public int deleteFailedOlderThan(int days, int limit) {
        return jdbc.sql("""
                delete from graph_snapshot where id in (
                  select id from graph_snapshot
                   where status = 'FAILED' and created_at < now() - make_interval(days => :d) limit :lim)""")
                .param("d", days).param("lim", limit).update();
    }
}
