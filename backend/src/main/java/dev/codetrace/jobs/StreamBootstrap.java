package dev.codetrace.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Idempotently creates the jobs stream and consumer group. Safe to run from every process, any number of times. */
@Component
public class StreamBootstrap implements ApplicationRunner {
    public static final String STREAM = "codetrace:jobs";
    public static final String GROUP = "indexers";
    private static final Logger log = LoggerFactory.getLogger(StreamBootstrap.class);

    private final StringRedisTemplate redis;

    public StreamBootstrap(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureGroup();
    }

    public void ensureGroup() {
        try {
            redis.opsForStream().createGroup(STREAM, ReadOffset.from("0"), GROUP);
            log.info("Created stream group {} on {}", GROUP, STREAM);
        } catch (DataAccessException e) {
            if (rootMessage(e).contains("BUSYGROUP")) {
                log.debug("Stream group {} already exists", GROUP);
            } else {
                throw e;
            }
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return String.valueOf(c.getMessage());
    }
}
