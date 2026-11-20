package dev.codetrace.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class MigrationIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.0-pg17").asCompatibleSubstituteFor("postgres"));

    private Flyway migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .cleanDisabled(false)
                .locations("classpath:db/migration")
                .load();
        flyway.clean();
        flyway.migrate();
        return flyway;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    @Test
    void migrationsApplyAndRerunningIsANoOp() throws SQLException {
        Flyway flyway = migrate();
        flyway.migrate(); // second run must change nothing
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
        assertThat(flyway.info().pending()).isEmpty();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("""
                     select count(*) from information_schema.tables where table_schema = 'public' and table_name in
                     ('schema_info','index_job','repository','revision','indexed_file','code_symbol','code_import',
                      'code_reference','parse_diagnostic','graph_snapshot','code_node','dependency_edge')""");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(12);
        }
    }

    @Test
    void idempotencyKeyIsUnique() throws SQLException {
        migrate();
        String key = "job:" + UUID.randomUUID();
        try (Connection c = connect()) {
            UUID repo = insertRepository(c, "https://github.com/o/r");
            insertJob(c, repo, "INDEX", key);
            assertThatThrownBy(() -> insertJob(c, repo, "INDEX", key))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_index_job_idempotency");
        }
    }

    @Test
    void schemaRejectsInvalidDataAndOrphans() throws SQLException {
        migrate();
        try (Connection c = connect()) {
            UUID repo = insertRepository(c, "https://github.com/o/other");
            assertThatThrownBy(() -> insertJob(c, repo, "EXECUTE", "k1"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_index_job_operation");
            assertThatThrownBy(() -> insertJob(c, UUID.randomUUID(), "INDEX", "k2"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("fk_index_job_repository");
            assertThatThrownBy(() -> insertRepository(c, "https://github.com/o/r2", "FTP"))
                    .isInstanceOf(SQLException.class);
            // same source cannot be registered twice
            assertThatThrownBy(() -> insertRepository(c, "https://github.com/o/other"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_repository_source");
            // commit ids must be full lowercase SHA-1
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "insert into revision (id, repository_id, commit_hash) values (?, ?, 'abc')")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, repo);
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void deletingARepositoryCascadesToItsJobsAndRevisions() throws SQLException {
        migrate();
        try (Connection c = connect()) {
            UUID repo = insertRepository(c, "https://github.com/o/cascade");
            insertJob(c, repo, "SYNC", "SYNC:" + repo);
            try (PreparedStatement ps = c.prepareStatement("delete from repository where id = ?")) {
                ps.setObject(1, repo);
                ps.executeUpdate();
            }
            for (String table : List.of("index_job", "revision")) {
                try (PreparedStatement ps = c.prepareStatement("select count(*) from " + table + " where repository_id = ?")) {
                    ps.setObject(1, repo);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        assertThat(rs.getInt(1)).as(table).isZero();
                    }
                }
            }
        }
    }

    // ---- graph tables --------------------------------------------------------------------------------------------

    private static final String COMMIT = "a".repeat(40);

    @Test
    void graphEdgesMustBeWellFormedAndStayInsideTheirSnapshot() throws SQLException {
        migrate();
        try (Connection c = connect()) {
            UUID repo = insertRepository(c, "https://github.com/o/graph1");
            UUID snap = insertSnapshot(c, repo, "BUILDING");
            UUID other = insertSnapshot(c, repo, "BUILDING", "b".repeat(40));
            UUID a = insertNode(c, snap, "FILE", "A.java");
            UUID b = insertNode(c, snap, "TYPE", "p.A");
            UUID foreign = insertNode(c, other, "TYPE", "p.A");

            // an UNRESOLVED edge must not have a target, and every other state must have one
            assertThatThrownBy(() -> insertEdge(c, snap, a, b, "REFERENCES", "UNRESOLVED", "0", ""))
                    .hasMessageContaining("ck_edge_unresolved_has_no_target");
            assertThatThrownBy(() -> insertEdge(c, snap, a, null, "REFERENCES", "RESOLVED", "1", ""))
                    .hasMessageContaining("ck_edge_unresolved_has_no_target");
            // AMBIGUOUS edges belong to an alternative group, others do not
            assertThatThrownBy(() -> insertEdge(c, snap, a, b, "REFERENCES", "AMBIGUOUS", "0.5", ""))
                    .hasMessageContaining("ck_edge_ambiguous_has_group");
            assertThatThrownBy(() -> insertEdge(c, snap, a, b, "REFERENCES", "RESOLVED", "1", "g1"))
                    .hasMessageContaining("ck_edge_ambiguous_has_group");
            // a resolved edge needs positive confidence; confidence stays within [0,1]
            assertThatThrownBy(() -> insertEdge(c, snap, a, b, "REFERENCES", "RESOLVED", "0", ""))
                    .hasMessageContaining("ck_edge_resolved_confidence");
            assertThatThrownBy(() -> insertEdge(c, snap, a, b, "REFERENCES", "RESOLVED", "1.5", ""))
                    .isInstanceOf(SQLException.class);
            // edges cannot reach into another snapshot
            assertThatThrownBy(() -> insertEdge(c, snap, a, foreign, "REFERENCES", "RESOLVED", "1", ""))
                    .hasMessageContaining("fk_edge_dst");
            assertThatThrownBy(() -> insertEdge(c, snap, UUID.randomUUID(), b, "REFERENCES", "RESOLVED", "1", ""))
                    .hasMessageContaining("fk_edge_src");

            insertEdge(c, snap, a, b, "CONTAINS", "RESOLVED", "1", "");
            assertThatThrownBy(() -> insertEdge(c, snap, a, b, "CONTAINS", "RESOLVED", "1", ""))
                    .hasMessageContaining("uq_dependency_edge");
            // the same unresolved name from one source is stored once, different names are separate edges
            insertEdge(c, snap, a, null, "REFERENCES", "UNRESOLVED", "0", "", "java.util.List");
            insertEdge(c, snap, a, null, "REFERENCES", "UNRESOLVED", "0", "", "java.util.Map");
            assertThatThrownBy(() -> insertEdge(c, snap, a, null, "REFERENCES", "UNRESOLVED", "0", "", "java.util.List"))
                    .hasMessageContaining("uq_dependency_edge");
            // node identity is unique per snapshot
            assertThatThrownBy(() -> insertNode(c, snap, "TYPE", "p.A"))
                    .hasMessageContaining("uq_code_node_identity");
        }
    }

    @Test
    void aPublishedSnapshotIsImmutable() throws SQLException {
        migrate();
        try (Connection c = connect()) {
            UUID repo = insertRepository(c, "https://github.com/o/graph2");
            UUID snap = insertSnapshot(c, repo, "BUILDING");
            UUID a = insertNode(c, snap, "FILE", "A.java");
            UUID b = insertNode(c, snap, "TYPE", "p.A");
            insertEdge(c, snap, a, b, "CONTAINS", "RESOLVED", "1", "");

            // while building, rows can still change
            exec(c, "update code_node set simple_name = 'x' where id = '" + b + "'");
            exec(c, "update graph_snapshot set status = 'VALIDATING' where id = '" + snap + "'");
            exec(c, "update graph_snapshot set status = 'PUBLISHED', published_at = now() where id = '" + snap + "'");

            assertThatThrownBy(() -> insertNode(c, snap, "TYPE", "p.B")).hasMessageContaining("immutable");
            assertThatThrownBy(() -> insertEdge(c, snap, a, b, "REFERENCES", "RESOLVED", "1", ""))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> exec(c, "update code_node set simple_name = 'y' where id = '" + b + "'"))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> exec(c, "delete from code_node where id = '" + b + "'"))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> exec(c, "delete from dependency_edge where snapshot_id = '" + snap + "'"))
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> exec(c, "update graph_snapshot set status = 'FAILED' where id = '" + snap + "'"))
                    .hasMessageContaining("immutable");
            // "published" requires a publication time
            UUID second = insertSnapshot(c, repo, "BUILDING", "c".repeat(40));
            assertThatThrownBy(() -> exec(c, "update graph_snapshot set status = 'PUBLISHED' where id = '" + second + "'"))
                    .hasMessageContaining("ck_graph_snapshot_published");
            assertThat(count(c, "code_node", snap)).isEqualTo(2);
        }
    }

    @Test
    void deletingASnapshotOrRepositoryStillCascadesThroughPublishedGraphs() throws SQLException {
        migrate();
        try (Connection c = connect()) {
            UUID repo = insertRepository(c, "https://github.com/o/graph3");
            UUID snapA = publishedSnapshotWithOneEdge(c, repo, COMMIT);
            UUID snapB = publishedSnapshotWithOneEdge(c, repo, "b".repeat(40));

            exec(c, "delete from graph_snapshot where id = '" + snapA + "'");
            assertThat(count(c, "code_node", snapA)).isZero();
            assertThat(count(c, "dependency_edge", snapA)).isZero();
            assertThat(count(c, "code_node", snapB)).isEqualTo(2);

            exec(c, "delete from repository where id = '" + repo + "'");
            assertThat(count(c, "code_node", snapB)).isZero();
            assertThat(count(c, "dependency_edge", snapB)).isZero();
        }
    }

    @Test
    void snapshotIdentityIsUniquePerRepositoryCommitAndVersions() throws SQLException {
        migrate();
        try (Connection c = connect()) {
            UUID repo = insertRepository(c, "https://github.com/o/graph4");
            insertSnapshot(c, repo, "REQUESTED");
            assertThatThrownBy(() -> insertSnapshot(c, repo, "REQUESTED"))
                    .hasMessageContaining("uq_graph_snapshot_identity");
        }
    }

    private static UUID publishedSnapshotWithOneEdge(Connection c, UUID repo, String commit) throws SQLException {
        UUID snap = insertSnapshot(c, repo, "BUILDING", commit);
        UUID a = insertNode(c, snap, "FILE", "A.java");
        UUID b = insertNode(c, snap, "TYPE", "p.A");
        insertEdge(c, snap, a, b, "CONTAINS", "RESOLVED", "1", "");
        exec(c, "update graph_snapshot set status = 'VALIDATING' where id = '" + snap + "'");
        exec(c, "update graph_snapshot set status = 'PUBLISHED', published_at = now() where id = '" + snap + "'");
        return snap;
    }

    private static UUID insertSnapshot(Connection c, UUID repo, String status) throws SQLException {
        return insertSnapshot(c, repo, status, COMMIT);
    }

    private static UUID insertSnapshot(Connection c, UUID repo, String status, String commit) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("""
                insert into graph_snapshot (id, repository_id, commit_hash, parser_version, builder_version, status)
                values (?, ?, ?, 'p1', 'b1', ?)""")) {
            ps.setObject(1, id);
            ps.setObject(2, repo);
            ps.setString(3, commit);
            ps.setString(4, status);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID insertNode(Connection c, UUID snapshot, String type, String qn) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("""
                insert into code_node (id, snapshot_id, repository_id, commit_hash, node_type, qualified_name, simple_name,
                    extraction_method, parser_version)
                values (?, ?, ?, ?, ?, ?, ?, 'm', 'p1')""")) {
            ps.setObject(1, id);
            ps.setObject(2, snapshot);
            ps.setObject(3, UUID.randomUUID());
            ps.setString(4, COMMIT);
            ps.setString(5, type);
            ps.setString(6, qn);
            ps.setString(7, qn);
            ps.executeUpdate();
        }
        return id;
    }

    private static void insertEdge(Connection c, UUID snapshot, UUID src, UUID dst, String type, String state,
                                   String confidence, String group) throws SQLException {
        insertEdge(c, snapshot, src, dst, type, state, confidence, group, "");
    }

    private static void insertEdge(Connection c, UUID snapshot, UUID src, UUID dst, String type, String state,
                                   String confidence, String group, String dstName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                insert into dependency_edge (snapshot_id, repository_id, commit_hash, src_node_id, dst_node_id, edge_type,
                    dst_name, resolution_state, confidence, evidence_kind, alternative_group, extraction_method,
                    resolver_version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::numeric, 'TEST', ?, 'm', 'v1')""")) {
            ps.setObject(1, snapshot);
            ps.setObject(2, UUID.randomUUID());
            ps.setString(3, COMMIT);
            ps.setObject(4, src);
            if (dst == null) ps.setNull(5, java.sql.Types.OTHER); else ps.setObject(5, dst);
            ps.setString(6, type);
            ps.setString(7, dstName);
            ps.setString(8, state);
            ps.setString(9, confidence);
            ps.setString(10, group);
            ps.executeUpdate();
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private static int count(Connection c, String table, UUID snapshot) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from " + table + " where snapshot_id = ?")) {
            ps.setObject(1, snapshot);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static UUID insertRepository(Connection c, String location) throws SQLException {
        return insertRepository(c, location, "GIT_URL");
    }

    private static UUID insertRepository(Connection c, String location, String type) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "insert into repository (id, source_type, location, display_name) values (?, ?, ?, 'x')")) {
            ps.setObject(1, id);
            ps.setString(2, type);
            ps.setString(3, location);
            ps.executeUpdate();
        }
        return id;
    }

    private static void insertJob(Connection c, UUID repo, String op, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                insert into index_job (id, repository_id, operation, status, idempotency_key)
                values (?, ?, ?, 'QUEUED', ?)""")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, repo);
            ps.setString(3, op);
            ps.setString(4, key);
            ps.executeUpdate();
        }
    }
}
