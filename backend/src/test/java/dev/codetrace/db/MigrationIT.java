package dev.codetrace.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        return flyway;
    }

    @Test
    void v1AppliesAndIsIdempotent() throws SQLException {
        migrate();
        // second run must be a no-op
        assertThat(migrate().info().current().getVersion().getVersion()).isEqualTo("1");
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             PreparedStatement ps = c.prepareStatement(
                     "select count(*) from information_schema.tables where table_name in ('index_job','schema_info')");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void idempotencyKeyIsUnique() throws SQLException {
        migrate();
        String key = "job:" + UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            insert(c, key);
            assertThatThrownBy(() -> insert(c, key))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_index_job_idempotency");
        }
    }

    private static void insert(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into index_job (id, operation, status, idempotency_key) values (?, 'INDEX', 'QUEUED', ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, key);
            ps.executeUpdate();
        }
    }
}
