package dev.codetrace.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisStreamBootstrapIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Test
    void ensureGroupIsIdempotent() {
        LettuceConnectionFactory cf = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        cf.afterPropertiesSet();
        try {
            StringRedisTemplate template = new StringRedisTemplate(cf);
            StreamBootstrap bootstrap = new StreamBootstrap(template);

            bootstrap.ensureGroup();
            bootstrap.ensureGroup(); // BUSYGROUP must be swallowed

            var groups = template.opsForStream().groups(StreamBootstrap.STREAM);
            assertThat(groups).hasSize(1);
            assertThat(groups.get(0).groupName()).isEqualTo(StreamBootstrap.GROUP);
        } finally {
            cf.destroy();
        }
    }
}
