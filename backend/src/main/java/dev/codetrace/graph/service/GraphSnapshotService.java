package dev.codetrace.graph.service;

import dev.codetrace.common.web.ApiException;
import dev.codetrace.graph.engine.GraphBuilder;
import dev.codetrace.graph.repository.GraphSnapshotRepository;
import dev.codetrace.graph.model.Snapshot;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor;
import dev.codetrace.jobs.model.IndexedRevision;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.jobs.model.JobKeys;
import dev.codetrace.jobs.model.JobOperation;
import dev.codetrace.jobs.service.JobService;
import dev.codetrace.source.model.SourceRepository;
import dev.codetrace.source.service.SourceRepositoryService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GraphSnapshotService {
    public record Requested(Snapshot snapshot, Job job) {
    }

    private final SourceRepositoryService repositories;
    private final JobService jobs;
    private final GraphSnapshotRepository snapshots;

    public GraphSnapshotService(SourceRepositoryService repositories, JobService jobs, GraphSnapshotRepository snapshots) {
        this.repositories = repositories;
        this.jobs = jobs;
        this.snapshots = snapshots;
    }

    /**
     * Requests a graph snapshot for an indexed commit. Idempotent: the same repository, commit, parser version and
     * builder version always map to one snapshot and one build job; a published snapshot is returned as is, a failed
     * one is queued again.
     */
    public Requested requestBuild(UUID repositoryId, String commitHash) {
        SourceRepository repo = repositories.get(repositoryId);
        String commit = repositories.resolveRevision(repo, commitHash);
        IndexedRevision indexed = jobs.findSucceededIndex(repo.id(), commit).orElseThrow(
                () -> ApiException.conflict("Index this revision first: no finished index job exists for it."));
        if (indexed.extractionVersion() == null || indexed.extractionVersion() < JavaSymbolExtractor.EXTRACTION_VERSION) {
            throw ApiException.conflict("This revision was indexed by an older extractor. Re-index it, then build the graph.");
        }

        GraphSnapshotRepository.Created snap = snapshots.createOrGet(repo.id(), commit, indexed.parserVersion(),
                GraphBuilder.VERSION, indexed.jobId());
        JobService.Submission build = jobs.submit(repo.id(), JobOperation.GRAPH_BUILD, commit,
                JobKeys.graph(repo.id(), commit, indexed.parserVersion(), GraphBuilder.VERSION));
        if (!snap.snapshot().published() && !build.created() && jobs.retryIfFailed(build.job())) {
            snapshots.requeue(snap.snapshot().id());
        }
        return new Requested(snapshots.find(snap.snapshot().id()).orElseThrow(), jobs.get(build.job().id()));
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

    /** The most recently published snapshot of a commit, if any. */
    public Optional<Snapshot> latestPublished(UUID repositoryId, String commitHash) {
        return snapshots.latestPublished(repositoryId, commitHash);
    }

    public List<Snapshot> list(UUID repositoryId) {
        repositories.get(repositoryId);
        return snapshots.listForRepository(repositoryId, 50);
    }
}
