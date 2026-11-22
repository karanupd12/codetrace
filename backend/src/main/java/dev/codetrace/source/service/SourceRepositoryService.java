package dev.codetrace.source.service;

import dev.codetrace.common.util.Commits;
import dev.codetrace.common.web.ApiException;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.jobs.model.JobKeys;
import dev.codetrace.jobs.model.JobOperation;
import dev.codetrace.jobs.service.JobService;
import dev.codetrace.source.model.SourceType;
import dev.codetrace.source.repository.SourceRepositoryRepository;
import dev.codetrace.source.model.Revision;
import dev.codetrace.source.model.SourceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SourceRepositoryService {
    public record Registered(SourceRepository repo, Job syncJob, boolean created) {
    }

    private final SourceRepositoryRepository repos;
    private final JobService jobs;
    private final SourceValidator validator;

    public SourceRepositoryService(SourceRepositoryRepository repos, JobService jobs, SourceValidator validator) {
        this.repos = repos;
        this.jobs = jobs;
        this.validator = validator;
    }

    public Registered register(SourceType type, String location, String displayName) {
        SourceValidator.Validated v = validator.validate(type, location);
        var existing = repos.findBySource(v.type(), v.location());
        if (existing.isPresent()) {
            SourceRepository repo = existing.get();
            return new Registered(repo, submitSync(repo).job(), false);
        }
        String name = displayName != null && !displayName.isBlank() ? displayName.trim() : v.displayName();
        SourceRepository repo;
        try {
            repo = repos.insert(UUID.randomUUID(), v.type(), v.location(), name);
        } catch (org.springframework.dao.DuplicateKeyException race) {
            repo = repos.findBySource(v.type(), v.location()).orElseThrow();
        }
        return new Registered(repo, submitSync(repo).job(), true);
    }

    public SourceRepository get(UUID id) {
        return repos.find(id).orElseThrow(() -> ApiException.notFound("Repository not found."));
    }

    public List<SourceRepository> list() {
        return repos.list(100);
    }

    /** For background work: the repository may have been deleted since the job was queued. */
    public Optional<SourceRepository> find(UUID id) {
        return repos.find(id);
    }

    public List<Revision> revisions(UUID repositoryId) {
        get(repositoryId);
        return repos.listRevisions(repositoryId, 200);
    }

    /** Re-run the sync (fetch new commits). Idempotent for jobs that are already queued or running. */
    public Job resync(UUID repositoryId) {
        SourceRepository repo = get(repositoryId);
        Job job = submitSync(repo).job();
        if (jobs.rerun(job.id())) repos.markSyncPending(repo.id());
        return jobs.get(job.id());
    }

    /** Request indexing of one pinned commit (defaults to the synced HEAD). */
    public Job requestIndex(UUID repositoryId, String commitHash) {
        SourceRepository repo = get(repositoryId);
        if (!"SYNCED".equals(repo.syncStatus())) {
            throw ApiException.conflict("Repository is not synced yet (status " + repo.syncStatus() + ").");
        }
        String commit = resolveRevision(repo, commitHash);
        JobService.Submission s = jobs.submit(repo.id(), JobOperation.INDEX, commit, JobKeys.index(repo.id(), commit));
        if (!s.created() && !jobs.retryIfFailed(s.job())
                && "SUCCEEDED".equals(s.job().status()) && indexedByOlderExtractor(repo.id(), commit)) {
            jobs.rerun(s.job().id()); // finished, but without facts newer stages need: extract again
        }
        // QUEUED/RUNNING and up-to-date SUCCEEDED jobs are returned as they are.
        return jobs.get(s.job().id());
    }

    /**
     * The commit a request refers to: blank means the synced HEAD, otherwise a full commit id that sync has
     * discovered. 409 if a blank request meets an unsynced repository, 400 for a malformed id, 404 for an unknown one.
     */
    public String resolveRevision(SourceRepository repo, String commitHash) {
        String commit;
        if (commitHash == null || commitHash.isBlank()) {
            if (!"SYNCED".equals(repo.syncStatus())) {
                throw ApiException.conflict("Repository is not synced yet (status " + repo.syncStatus() + ").");
            }
            commit = repo.headCommit();
        } else if (Commits.isFullSha1(commitHash)) {
            commit = Commits.normalize(commitHash);
        } else {
            throw ApiException.badRequest("commitHash must be a full 40-character commit id.");
        }
        if (commit == null || !repos.revisionExists(repo.id(), commit)) {
            throw ApiException.notFound("Revision is not known for this repository. List revisions first.");
        }
        return commit;
    }

    /** A full commit id named by {@code field} that sync has discovered: 400 if malformed, 404 if unknown. */
    public String requireRevision(SourceRepository repo, String commitHash, String field) {
        if (!Commits.isFullSha1(commitHash)) {
            throw ApiException.badRequest(field + " must be a full 40-character commit id.");
        }
        String commit = Commits.normalize(commitHash);
        if (!repos.revisionExists(repo.id(), commit)) {
            throw ApiException.notFound("Revision " + Commits.shortHash(commit)
                    + " is not known for this repository. List revisions first.");
        }
        return commit;
    }

    /** Records what a sync found: the discovered commits, then the default branch and HEAD. */
    public void recordSync(UUID repositoryId, String defaultBranch, String headCommit, List<Revision> discovered) {
        for (Revision r : discovered) repos.upsertRevision(repositoryId, r.commitHash(), r.summary(), r.authorTime());
        repos.markSynced(repositoryId, defaultBranch, headCommit);
    }

    public void markSyncFailed(UUID repositoryId) {
        repos.markSyncFailed(repositoryId);
    }

    public List<Job> jobsFor(UUID repositoryId) {
        get(repositoryId);
        return jobs.listForRepository(repositoryId, 50);
    }

    private JobService.Submission submitSync(SourceRepository repo) {
        return jobs.submit(repo.id(), JobOperation.SYNC, null, JobKeys.sync(repo.id()));
    }

    private boolean indexedByOlderExtractor(UUID repositoryId, String commit) {
        return jobs.findSucceededIndex(repositoryId, commit)
                .map(i -> !i.isCurrent())
                .orElse(false);
    }
}
