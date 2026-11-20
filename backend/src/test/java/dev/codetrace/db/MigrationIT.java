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
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(flyway.info().pending()).isEmpty();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("""
                     select count(*) from information_schema.tables where table_schema = 'public' and table_name in
                     ('schema_info','index_job','repository','revision','indexed_file','code_symbol','code_import',
                      'parse_diagnostic')""");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(8);
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
