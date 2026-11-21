package dev.codetrace.ingestion.service;

import dev.codetrace.ingestion.git.GitSourceManager;
import dev.codetrace.ingestion.git.RevisionDiscovery;
import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.jobs.JobHandler;
import dev.codetrace.jobs.model.JobOperation;
import dev.codetrace.source.repository.SourceRepositoryRepository;
import dev.codetrace.source.repository.SourceRepositoryRepository.Repo;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** SYNC: fetch (or open) the repository and record its most recent commits. */
@Component
@Profile("worker")
public class SyncJobHandler implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(SyncJobHandler.class);

    private final SourceRepositoryRepository repos;
    private final GitSourceManager git;
    private final RevisionDiscovery revisions;

    public SyncJobHandler(SourceRepositoryRepository repos, GitSourceManager git, RevisionDiscovery revisions) {
        this.repos = repos;
        this.git = git;
        this.revisions = revisions;
    }

    @Override
    public JobOperation operation() {
        return JobOperation.SYNC;
    }

    @Override
    public void handle(Job job) {
        Repo repo = repos.find(job.repositoryId()).orElseThrow(
                () -> new JobFailureException(Category.INVALID_SOURCE, "Repository no longer exists."));
        try (Repository r = git.open(repo)) {
            RevisionDiscovery.Result found = revisions.discover(r);
            for (var rev : found.revisions()) {
                repos.upsertRevision(repo.id(), rev.commitHash(), rev.summary(), rev.authorTime());
            }
            repos.markSynced(repo.id(), found.defaultBranch(), found.headCommit());
            log.info("job {} synced {} revisions", job.id(), found.revisions().size());
        }
    }

    @Override
    public void onPermanentFailure(Job job) {
        repos.markSyncFailed(job.repositoryId());
    }
}
