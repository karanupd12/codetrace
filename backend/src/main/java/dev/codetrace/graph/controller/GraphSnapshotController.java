package dev.codetrace.graph.controller;

import dev.codetrace.graph.service.GraphSnapshotService;
import dev.codetrace.graph.repository.GraphSnapshotRepository.Snapshot;
import dev.codetrace.source.controller.SourceRepositoryController.JobView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Requesting and inspecting graph snapshots. The graph contents are served by {@link GraphQueryController}. */
@RestController
@Profile("api")
@RequestMapping("/api/v1")
public class GraphSnapshotController {
    public record BuildRequest(@Size(max = 64) String commitHash) {
    }

    public record SnapshotView(UUID id, UUID repositoryId, String commitHash, String parserVersion,
                               String builderVersion, String status, Integer nodeCount, Integer edgeCount,
                               Integer resolvedEdgeCount, Integer ambiguousEdgeCount, Integer unresolvedEdgeCount,
                               String failureReason, OffsetDateTime createdAt, OffsetDateTime publishedAt) {
        public static SnapshotView of(Snapshot s) {
            return new SnapshotView(s.id(), s.repositoryId(), s.commitHash(), s.parserVersion(), s.builderVersion(),
                    s.status(), s.nodeCount(), s.edgeCount(), s.resolvedEdgeCount(), s.ambiguousEdgeCount(),
                    s.unresolvedEdgeCount(), s.failureReason(), s.createdAt(), s.publishedAt());
        }
    }

    public record BuildView(SnapshotView snapshot, JobView job) {
    }

    private final GraphSnapshotService service;

    public GraphSnapshotController(GraphSnapshotService service) {
        this.service = service;
    }

    @PostMapping("/repositories/{id}/graph-snapshots")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BuildView build(@PathVariable UUID id, @Valid @RequestBody(required = false) BuildRequest req) {
        GraphSnapshotService.Requested r = service.requestBuild(id, req == null ? null : req.commitHash());
        return new BuildView(SnapshotView.of(r.snapshot()), JobView.of(r.job()));
    }

    @GetMapping("/repositories/{id}/snapshots")
    public List<SnapshotView> list(@PathVariable UUID id) {
        return service.list(id).stream().map(SnapshotView::of).toList();
    }

    @GetMapping("/snapshots/{snapshotId}")
    public SnapshotView get(@PathVariable UUID snapshotId) {
        return SnapshotView.of(service.get(snapshotId));
    }
}
