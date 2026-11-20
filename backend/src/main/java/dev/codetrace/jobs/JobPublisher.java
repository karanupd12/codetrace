package dev.codetrace.jobs;

import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Publishes "look at job X" messages. Job state lives in PostgreSQL; a lost or duplicated message is harmless. */
@Component
public class JobPublisher {
    public static final String DEAD_STREAM = "codetrace:jobs:dead";

    private final StringRedisTemplate redis;

    public JobPublisher(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Returns the stream record id (useful for tests and tracing). */
    public String publish(UUID jobId) {
        RecordId id = redis.opsForStream().add(StreamRecords.string(Map.of("jobId", jobId.toString()))
                .withStreamKey(StreamBootstrap.STREAM));
        return id == null ? null : id.getValue();
    }

    public void publishDead(UUID jobId, String category) {
        redis.opsForStream().add(StreamRecords.string(Map.of("jobId", jobId.toString(), "category", category))
                .withStreamKey(DEAD_STREAM));
    }
}
