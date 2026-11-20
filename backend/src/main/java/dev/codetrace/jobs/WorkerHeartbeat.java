package dev.codetrace.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** M1 worker placeholder: proves the worker role runs. Job consumption arrives in M2. */
@Component
@Profile("worker")
@EnableScheduling
public class WorkerHeartbeat {
    private static final Logger log = LoggerFactory.getLogger(WorkerHeartbeat.class);

    @Scheduled(fixedDelayString = "${codetrace.worker.heartbeat-ms:30000}")
    public void beat() {
        log.info("worker heartbeat: stream={} group={}", StreamBootstrap.STREAM, StreamBootstrap.GROUP);
    }
}
