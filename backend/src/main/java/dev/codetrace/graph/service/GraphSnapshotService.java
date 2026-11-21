package dev.codetrace.graph.service;

import dev.codetrace.graph.engine.GraphBuilder;
import dev.codetrace.graph.repository.GraphSnapshotRepository;
import dev.codetrace.graph.repository.GraphSnapshotRepository.Snapshot;
import dev.codetrace.jobs.repository.JobRepository;
import dev.codetrace.jobs.repository.JobRepository.IndexedRevision;
import dev.codetrace.jobs.repository.JobRepository.Job;
import dev.codetrace.jobs.queue.JobPublisher;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor;
import dev.codetrace.source.repository.SourceRepositoryRepository;
import dev.codetrace.source.repository.SourceRepositoryRepository.Repo;
import dev.codetrace.common.web.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class GraphSnapshotService {
    private static final Pattern SHA1 = Pattern.compile("^[0-9a-fA-F]{40}$");

    public record Requested(Snapshot snapshot, Job job) {
    }

    private final SourceRepositoryRepository repos;
    private final JobRepository jobs;
    private final GraphSnapshotRepository snapshots;
    private final JobPublisher publisher;

    public GraphSnapshotService(SourceRepositoryRepository repos, JobRepository jobs, GraphSnapshotRepository snapshots, JobPublisher publisher) {
        this.repos = repos;
        this.jobs = jobs;
        this.snapshots = snapshots;
        this.publisher = publisher;
    }

    /**
     * Requests a graph snapshot for an indexed commit. Idempotent: the same repository, commit, parser version and
     * builder version always map to one snapshot and one build job; a published snapshot is returned as is, a failed
     * one is queued again.
     */
    public Requested requestBuild(UUID repositoryId, String commitHash) {
        Repo repo = repos.find(repositoryId).orElseThrow(() -> ApiException.notFound("Repository not found."));
        String commit = resolveCommit(repo, commitHash);
        IndexedRevision indexed = jobs.findSucceededIndex(repo.id(), commit).orElseThrow(
                () -> ApiException.conflict("Index this revision first: no finished index job exists for it."));
        if (indexed.extractionVersion() == null || indexed.extractionVersion() < JavaSymbolExtractor.EXTRACTION_VERSION) {
            throw ApiException.conflict("This revision was indexed by an older extractor. Re-index it, then build the graph.");
        }

        GraphSnapshotRepository.Created snap = snapshots.createOrGet(repo.id(), commit, indexed.parserVersion(),
                GraphBuilder.VERSION, indexed.jobId());
        JobRepository.Created build = jobs.createOrGet(repo.id(), GraphBuildHandler.OPERATION, commit,
                JobRepository.graphKey(repo.id(), commit, indexed.parserVersion(), GraphBuilder.VERSION));
        Job job = build.job();

        if (!snap.snapshot().published()) {
            if (build.created()) {
                publisher.publish(job.id());
            } else if (("FAILED".equals(job.status()) || "DEAD_LETTERED".equals(job.status())) && jobs.resetForRerun(job.id())) {
                snapshots.requeue(snap.snapshot().id());
                publisher.publish(job.id());
            }
        }
        return new Requested(snapshots.find(snap.snapshot().id()).orElseThrow(), jobs.find(job.id()).orElseThrow());
    }

    public Snapshot get(UUID snapshotId) {
        return snapshots.find(snapshotId).orElseThrow(() -> ApiException.notFound("Snapshot not found."));
    }

    /** A snapshot whose graph may be read: only PUBLISHED snapshots are ever exposed. */
    public Snapshot getPublished(UUID snapshotId) {
        Snapshot s = get(snapshotId);
        if (!s.published()) {
            throw ApiException.conflict("Snapshot is " + s.status() + "; its graph is readable only once published.");
        }
        return s;
    }

    public List<Snapshot> list(UUID repositoryId) {
        repos.find(repositoryId).orElseThrow(() -> ApiException.notFound("Repository not found."));
        return snapshots.listForRepository(repositoryId, 50);
    }

    private String resolveCommit(Repo repo, String commitHash) {
        String commit;
        if (commitHash == null || commitHash.isBlank()) {
            if (!"SYNCED".equals(repo.syncStatus())) {
                throw ApiException.conflict("Repository is not synced yet (status " + repo.syncStatus() + ").");
            }
            commit = repo.headCommit();
        } else if (SHA1.matcher(commitHash.trim()).matches()) {
            commit = commitHash.trim().toLowerCase(Locale.ROOT);
        } else {
            throw ApiException.badRequest("commitHash must be a full 40-character commit id.");
        }
        if (commit == null || !repos.revisionExists(repo.id(), commit)) {
            throw ApiException.notFound("Revision is not known for this repository. List revisions first.");
        }
        return commit;
    }
}
