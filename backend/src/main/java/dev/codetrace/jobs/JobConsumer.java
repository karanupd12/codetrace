package dev.codetrace.jobs;

import dev.codetrace.config.IngestionProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Single-threaded Redis Streams consumer. Messages are acknowledged only after the job reached a terminal or
 * retry-scheduled state in PostgreSQL; unacknowledged messages idle past the stale threshold are reclaimed
 * (covers worker crashes). Redelivery is safe because {@link JobProcessor} claims jobs atomically.
 */
@Component
@Profile("worker")
public class JobConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(JobConsumer.class);

    private final StringRedisTemplate redis;
    private final JobProcessor processor;
    private final IngestionProperties props;
    private final String consumerName = "worker-" + UUID.randomUUID();
    private volatile boolean running;
    private Thread thread;

    public JobConsumer(StringRedisTemplate redis, JobProcessor processor, IngestionProperties props) {
        this.redis = redis;
        this.processor = processor;
        this.props = props;
    }

    @Override
    public void start() {
        running = true;
        thread = new Thread(this::loop, "job-consumer");
        thread.setDaemon(true);
        thread.start();
        log.info("job consumer {} started", consumerName);
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        Consumer consumer = Consumer.from(StreamBootstrap.GROUP, consumerName);
        StreamReadOptions options = StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2));
        long lastReclaim = 0;
        while (running) {
            try {
                if (System.currentTimeMillis() - lastReclaim > 15_000) {
                    lastReclaim = System.currentTimeMillis();
                    reclaimStale(ops);
                }
                List<MapRecord<String, Object, Object>> records =
                        ops.read(consumer, options, StreamOffset.create(StreamBootstrap.STREAM, ReadOffset.lastConsumed()));
                if (records == null) continue;
                for (MapRecord<String, Object, Object> record : records) {
                    handle(ops, record);
                }
            } catch (Exception e) {
                if (!running) break;
                log.warn("job consumer error: {}", e.getClass().getSimpleName());
                sleep(1000);
            }
        }
    }

    private void reclaimStale(StreamOperations<String, Object, Object> ops) {
        PendingMessages pending = ops.pending(StreamBootstrap.STREAM, StreamBootstrap.GROUP, Range.unbounded(), 10);
        if (pending == null || pending.isEmpty()) return;
        Duration minIdle = Duration.ofSeconds(props.staleRunningSeconds());
        for (PendingMessage m : pending) {
            if (m.getElapsedTimeSinceLastDelivery().compareTo(minIdle) < 0) continue;
            List<MapRecord<String, Object, Object>> claimed =
                    ops.claim(StreamBootstrap.STREAM, StreamBootstrap.GROUP, consumerName, minIdle, m.getId());
            if (claimed == null) continue;
            for (MapRecord<String, Object, Object> record : claimed) {
                log.info("reclaimed stale message {}", record.getId());
                handle(ops, record);
            }
        }
    }

    private void handle(StreamOperations<String, Object, Object> ops, MapRecord<String, Object, Object> record) {
        UUID jobId;
        try {
            jobId = UUID.fromString(String.valueOf(record.getValue().get("jobId")));
        } catch (IllegalArgumentException e) {
            log.warn("dropping malformed message {}", record.getId());
            ops.acknowledge(StreamBootstrap.STREAM, StreamBootstrap.GROUP, record.getId());
            return;
        }
        try {
            processor.process(jobId);
            ops.acknowledge(StreamBootstrap.STREAM, StreamBootstrap.GROUP, record.getId());
        } catch (RuntimeException e) {
            // Infrastructure fault: leave unacknowledged so the message is reclaimed and retried.
            log.warn("job {} left pending after infrastructure error: {}", jobId, e.getClass().getSimpleName());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
