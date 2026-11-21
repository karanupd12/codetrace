package dev.codetrace.system.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The database schema version applied by Flyway. */
@Repository
public class SchemaInfoRepository {
    private final JdbcTemplate jdbc;

    public SchemaInfoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty when the database cannot be reached or no migration has run. */
    public Optional<String> latestMigrationVersion() {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "select version from flyway_schema_history where success order by installed_rank desc limit 1",
                    String.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
