package dev.codetrace.jobs.queue;

import dev.codetrace.jobs.repository.JobRepository;
import dev.codetrace.config.IngestionProperties;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Republishes QUEUED jobs that sat untouched for the retry delay. This provides the retry backoff and also recovers
 * jobs whose original publish was lost. Duplicates are harmless: the processor claims atomically.
 */
@Component
@Profile("worker")
public class QueuedJobSweeper {
    private static final Logger log = LoggerFactory.getLogger(QueuedJobSweeper.class);

    private final JobRepository jobs;
    private final JobPublisher publisher;
    private final IngestionProperties props;

    public QueuedJobSweeper(JobRepository jobs, JobPublisher publisher, IngestionProperties props) {
        this.jobs = jobs;
        this.publisher = publisher;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${codetrace.worker.sweep-ms:15000}")
    public void sweep() {
        try {
            List<UUID> ids = jobs.touchStaleQueued(props.retryDelaySeconds(), 50);
            for (UUID id : ids) {
                publisher.publish(id);
            }
            if (!ids.isEmpty()) {
                log.info("republished {} stale queued job(s)", ids.size());
            }
        } catch (RuntimeException e) {
            log.warn("sweeper error: {}", e.getClass().getSimpleName());
        }
    }
}
