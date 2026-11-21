package dev.codetrace.system.service;

import dev.codetrace.config.RoleProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import dev.codetrace.system.repository.SchemaInfoRepository;
import org.springframework.stereotype.Service;

@Service
public class SystemInfoService {
    private final SchemaInfoRepository schema;
    private final RedisConnectionFactory redis;
    private final RoleProperties role;
    private final ObjectProvider<BuildProperties> build;

    public SystemInfoService(SchemaInfoRepository schema, RedisConnectionFactory redis, RoleProperties role,
                             ObjectProvider<BuildProperties> build) {
        this.schema = schema;
        this.redis = redis;
        this.role = role;
        this.build = build;
    }

    public SystemInfo info() {
        BuildProperties bp = build.getIfAvailable();
        return new SystemInfo("codetrace", bp != null ? bp.getVersion() : "dev", role.role(),
                schema.latestMigrationVersion().orElse(null), redisStatus());
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
