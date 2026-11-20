package dev.codetrace.jobs;

import dev.codetrace.config.IngestionProperties;
import dev.codetrace.ingestion.GitSourceManager;
import dev.codetrace.ingestion.IngestionException;
import dev.codetrace.ingestion.IngestionException.Category;
import dev.codetrace.ingestion.JavaIndexer;
import dev.codetrace.ingestion.RevisionDiscovery;
import dev.codetrace.jobs.JobDao.Job;
import dev.codetrace.repository.RepositoryDao;
import dev.codetrace.repository.RepositoryDao.Repo;
import dev.codetrace.symbols.IndexResult;
import dev.codetrace.symbols.IndexResultWriter;
import dev.codetrace.system.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs one job. Safe to call any number of times for the same job id (Redis may redeliver):
 * the atomic DB claim lets exactly one caller run it, and finished jobs are never re-run.
 *
 * <p>Returns normally for every job outcome (success, failure, dead-letter, skipped duplicate), so the caller can
 * acknowledge the message. It throws only for infrastructure faults (e.g. database down) where the message must stay
 * pending for redelivery.
 */
@Component
@Profile("worker")
public class JobProcessor {
    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    private final JobDao jobs;
    private final RepositoryDao repos;
    private final GitSourceManager git;
    private final RevisionDiscovery revisions;
    private final JavaIndexer indexer;
    private final IndexResultWriter writer;
    private final JobPublisher publisher;
    private final IngestionProperties props;

    public JobProcessor(JobDao jobs, RepositoryDao repos, GitSourceManager git, RevisionDiscovery revisions,
                        JavaIndexer indexer, IndexResultWriter writer, JobPublisher publisher,
                        IngestionProperties props) {
        this.jobs = jobs;
        this.repos = repos;
        this.git = git;
        this.revisions = revisions;
        this.indexer = indexer;
        this.writer = writer;
        this.publisher = publisher;
        this.props = props;
    }

    public void process(UUID jobId) {
        if (!jobs.claim(jobId, props.staleRunningSeconds())) {
            log.debug("job {} not claimable (finished, running elsewhere, or unknown); skipping", jobId);
            return;
        }
        Job job = jobs.find(jobId).orElseThrow();
        if (job.attempts() > props.maxAttempts()) {
            deadLetter(job, "INTERNAL", "Attempts exhausted after worker interruptions.");
            return;
        }
        log.info("job {} {} attempt {}/{}", job.id(), job.operation(), job.attempts(), props.maxAttempts());
        try {
            if ("SYNC".equals(job.operation())) {
                runSync(job);
            } else {
                runIndex(job);
            }
        } catch (IngestionException e) {
            handleFailure(job, e.category(), e.getMessage());
        } catch (ApiException e) {
            handleFailure(job, Category.INVALID_SOURCE, e.getMessage());
        } catch (org.springframework.dao.DataAccessException e) {
            throw e; // infrastructure fault: leave the message pending
        } catch (RuntimeException e) {
            // Only the type is recorded: exception messages may contain paths or repository content.
            log.warn("job {} failed unexpectedly: {}", job.id(), e.getClass().getName());
            handleFailure(job, Category.INTERNAL, "Unexpected error (" + e.getClass().getSimpleName() + ").");
        }
    }

    private void runSync(Job job) {
        Repo repo = repos.find(job.repositoryId()).orElseThrow(
                () -> new IngestionException(Category.INVALID_SOURCE, "Repository no longer exists."));
        try (Repository r = git.open(repo)) {
            RevisionDiscovery.Result found = revisions.discover(r);
            for (var rev : found.revisions()) {
                repos.upsertRevision(repo.id(), rev.commitHash(), rev.summary(), rev.authorTime());
            }
            repos.markSynced(repo.id(), found.defaultBranch(), found.headCommit());
            jobs.markSyncSucceeded(job.id());
            log.info("job {} synced {} revisions", job.id(), found.revisions().size());
        }
    }

    private void runIndex(Job job) {
        Repo repo = repos.find(job.repositoryId()).orElseThrow(
                () -> new IngestionException(Category.INVALID_SOURCE, "Repository no longer exists."));
        try (Repository r = git.open(repo)) {
            IndexResult result = indexer.index(r, job.commitHash());
            writer.write(job.id(), repo.id(), job.commitHash(), result);
            log.info("job {} indexed {} files, {} symbols", job.id(), result.filesParsed(), result.symbols().size());
        }
    }

    private void handleFailure(Job job, Category category, String message) {
        if (category.retryable() && job.attempts() < props.maxAttempts()) {
            log.info("job {} retryable failure ({}); will retry after delay", job.id(), category);
            jobs.markForRetry(job.id(), category.name(), message);
        } else if (category.retryable()) {
            deadLetter(job, category.name(), message);
        } else {
            log.info("job {} failed permanently ({})", job.id(), category);
            jobs.markFailed(job.id(), category.name(), message);
            if ("SYNC".equals(job.operation())) {
                repos.markSyncFailed(job.repositoryId());
            }
        }
    }

    private void deadLetter(Job job, String category, String message) {
        log.warn("job {} dead-lettered ({})", job.id(), category);
        jobs.markDeadLettered(job.id(), category, message);
        if ("SYNC".equals(job.operation())) {
            repos.markSyncFailed(job.repositoryId());
        }
        publisher.publishDead(job.id(), category);
    }

    /** Visible for tests. */
    Optional<Job> find(UUID id) {
        return jobs.find(id);
    }
}
