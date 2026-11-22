package dev.codetrace.ingestion.service;

import dev.codetrace.ingestion.git.GitSourceManager;
import dev.codetrace.ingestion.git.RevisionDiscovery;
import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.jobs.JobHandler;
import dev.codetrace.jobs.model.JobOperation;
import dev.codetrace.jobs.model.JobStep;
import dev.codetrace.jobs.service.JobService;
import dev.codetrace.source.model.SourceType;
import dev.codetrace.source.model.Revision;
import dev.codetrace.source.service.SourceRepositoryService;
import dev.codetrace.source.model.SourceRepository;
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

    private final SourceRepositoryService repositories;
    private final GitSourceManager git;
    private final RevisionDiscovery revisions;

    private final JobService jobs;

    public SyncJobHandler(SourceRepositoryService repositories, GitSourceManager git, RevisionDiscovery revisions,
                          JobService jobs) {
        this.jobs = jobs;
        this.repositories = repositories;
        this.git = git;
        this.revisions = revisions;
    }

    @Override
    public JobOperation operation() {
        return JobOperation.SYNC;
    }

    @Override
    public void handle(Job job) {
        SourceRepository repo = repositories.find(job.repositoryId()).orElseThrow(
                () -> new JobFailureException(Category.INVALID_SOURCE, "Repository no longer exists."));
        jobs.progress(job, JobStep.DOWNLOADING, repo.sourceType() == SourceType.GIT_URL
                ? "Downloading the latest history" : "Reading the local repository");
        try (Repository r = git.open(repo)) {
            RevisionDiscovery.Result found = revisions.discover(r);
            jobs.progress(job, JobStep.VERSIONS_FOUND, "Found " + found.revisions().size() + " versions",
                    java.util.Map.of("versions", found.revisions().size()));
            repositories.recordSync(repo.id(), found.defaultBranch(), found.headCommit(), found.revisions().stream()
                    .map(d -> new Revision(null, d.commitHash(), d.summary(), d.authorTime())).toList());
            log.info("job {} synced {} revisions", job.id(), found.revisions().size());
        }
    }

    @Override
    public void onPermanentFailure(Job job) {
        repositories.markSyncFailed(job.repositoryId());
    }
}
