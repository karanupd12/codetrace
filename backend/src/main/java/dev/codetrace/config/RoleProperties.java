package dev.codetrace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** codetrace.role is "api" or "worker"; set from the active profile in application-*.yml. */
@ConfigurationProperties(prefix = "codetrace")
public record RoleProperties(String role, String corsOrigins) {
}
