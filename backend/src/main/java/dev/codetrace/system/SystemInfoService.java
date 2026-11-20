package dev.codetrace.system;

import dev.codetrace.config.RoleProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SystemInfoService {
    private final JdbcTemplate jdbc;
    private final RedisConnectionFactory redis;
    private final RoleProperties role;
    private final ObjectProvider<BuildProperties> build;

    public SystemInfoService(JdbcTemplate jdbc, RedisConnectionFactory redis, RoleProperties role,
                             ObjectProvider<BuildProperties> build) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.role = role;
        this.build = build;
    }

    public SystemInfo info() {
        BuildProperties bp = build.getIfAvailable();
        return new SystemInfo("codetrace", bp != null ? bp.getVersion() : "dev", role.role(),
                migrationVersion(), redisStatus());
    }

    private String migrationVersion() {
        try {
            return jdbc.queryForObject(
                    "select version from flyway_schema_history where success order by installed_rank desc limit 1",
                    String.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String redisStatus() {
        try (var conn = redis.getConnection()) {
            return "PONG".equalsIgnoreCase(conn.ping()) ? "UP" : "DOWN";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    public record SystemInfo(String name, String version, String role, String dbMigrationVersion, String redis) {
    }
}
