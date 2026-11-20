package dev.codetrace.repository;

import dev.codetrace.jobs.JobDao;
import dev.codetrace.jobs.JobDao.Created;
import dev.codetrace.jobs.JobDao.Job;
import dev.codetrace.jobs.JobPublisher;
import dev.codetrace.parser.JavaSymbolExtractor;
import dev.codetrace.repository.RepositoryDao.Repo;
import dev.codetrace.security.SourceValidator;
import dev.codetrace.system.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class RepositoryService {
    private static final Pattern SHA1 = Pattern.compile("^[0-9a-fA-F]{40}$");

    public record Registered(Repo repo, Job syncJob, boolean created) {
    }

    private final RepositoryDao repos;
    private final JobDao jobs;
    private final JobPublisher publisher;
    private final SourceValidator validator;

    public RepositoryService(RepositoryDao repos, JobDao jobs, JobPublisher publisher, SourceValidator validator) {
        this.repos = repos;
        this.jobs = jobs;
        this.publisher = publisher;
        this.validator = validator;
    }

    public Registered register(SourceType type, String location, String displayName) {
        SourceValidator.Validated v = validator.validate(type, location);
        var existing = repos.findBySource(v.type(), v.location());
        if (existing.isPresent()) {
            Repo repo = existing.get();
            Job sync = jobs.createOrGet(repo.id(), "SYNC", null, JobDao.syncKey(repo.id())).job();
            return new Registered(repo, sync, false);
        }
        String name = displayName != null && !displayName.isBlank() ? displayName.trim() : v.displayName();
        Repo repo;
        try {
            repo = repos.insert(UUID.randomUUID(), v.type(), v.location(), name);
        } catch (org.springframework.dao.DuplicateKeyException race) {
            repo = repos.findBySource(v.type(), v.location()).orElseThrow();
        }
        Created sync = jobs.createOrGet(repo.id(), "SYNC", null, JobDao.syncKey(repo.id()));
        if (sync.created()) {
            publisher.publish(sync.job().id());
        }
        return new Registered(repo, sync.job(), true);
    }

    public Repo get(UUID id) {
        return repos.find(id).orElseThrow(() -> ApiException.notFound("Repository not found."));
    }

    public List<Repo> list() {
        return repos.list(100);
    }

    public List<RepositoryDao.Revision> revisions(UUID repositoryId) {
        get(repositoryId);
        return repos.listRevisions(repositoryId, 200);
    }

    /** Re-run the sync (fetch new commits). Idempotent for jobs that are already queued or running. */
    public Job resync(UUID repositoryId) {
        Repo repo = get(repositoryId);
        Job job = jobs.createOrGet(repo.id(), "SYNC", null, JobDao.syncKey(repo.id())).job();
        if (jobs.resetForRerun(job.id())) {
            repos.markSyncPending(repo.id());
            publisher.publish(job.id());
        }
        return jobs.find(job.id()).orElseThrow();
    }

    /** Request indexing of one pinned commit (defaults to the synced HEAD). */
    public Job requestIndex(UUID repositoryId, String commitHash) {
        Repo repo = get(repositoryId);
        if (!"SYNCED".equals(repo.syncStatus())) {
            throw ApiException.conflict("Repository is not synced yet (status " + repo.syncStatus() + ").");
        }
        String commit;
        if (commitHash == null || commitHash.isBlank()) {
            commit = repo.headCommit();
        } else if (SHA1.matcher(commitHash.trim()).matches()) {
            commit = commitHash.trim().toLowerCase(Locale.ROOT);
        } else {
            throw ApiException.badRequest("commitHash must be a full 40-character commit id.");
        }
        if (commit == null || !repos.revisionExists(repo.id(), commit)) {
            throw ApiException.notFound("Revision is not known for this repository. List revisions first.");
        }
        Created created = jobs.createOrGet(repo.id(), "INDEX", commit, JobDao.indexKey(repo.id(), commit));
        Job job = created.job();
        if (created.created()) {
            publisher.publish(job.id());
        } else if (("FAILED".equals(job.status()) || "DEAD_LETTERED".equals(job.status()))
                && jobs.resetForRerun(job.id())) {
            publisher.publish(job.id()); // previously failed: retry it
        } else if ("SUCCEEDED".equals(job.status()) && indexedByOlderExtractor(repo.id(), commit)
                && jobs.resetForRerun(job.id())) {
            publisher.publish(job.id()); // finished, but without facts newer stages need: extract again
        }
        // QUEUED/RUNNING and up-to-date SUCCEEDED jobs are returned as they are.
        return jobs.find(job.id()).orElseThrow();
    }

    private boolean indexedByOlderExtractor(UUID repositoryId, String commit) {
        return jobs.findSucceededIndex(repositoryId, commit)
                .map(i -> i.extractionVersion() == null || i.extractionVersion() < JavaSymbolExtractor.EXTRACTION_VERSION)
                .orElse(false);
    }

    public Job job(UUID jobId) {
        return jobs.find(jobId).orElseThrow(() -> ApiException.notFound("Job not found."));
    }

    public List<Job> jobsFor(UUID repositoryId) {
        get(repositoryId);
        return jobs.listForRepository(repositoryId, 50);
    }
}
