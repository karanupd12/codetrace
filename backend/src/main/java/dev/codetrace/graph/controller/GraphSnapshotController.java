package dev.codetrace.graph.controller;

import dev.codetrace.graph.controller.dto.BuildRequest;
import dev.codetrace.graph.controller.dto.BuildResponse;
import dev.codetrace.graph.controller.dto.SnapshotResponse;
import dev.codetrace.graph.service.GraphSnapshotService;
import dev.codetrace.jobs.controller.dto.JobResponse;
import jakarta.validation.Valid;
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
    private final GraphSnapshotService service;

    public GraphSnapshotController(GraphSnapshotService service) {
        this.service = service;
    }

    @PostMapping("/repositories/{id}/graph-snapshots")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BuildResponse build(@PathVariable UUID id, @Valid @RequestBody(required = false) BuildRequest req) {
        GraphSnapshotService.Requested r = service.requestBuild(id, req == null ? null : req.commitHash());
        return new BuildResponse(SnapshotResponse.from(r.snapshot()), JobResponse.from(r.job()));
    }

    @GetMapping("/repositories/{id}/snapshots")
    public List<SnapshotResponse> list(@PathVariable UUID id) {
        return service.list(id).stream().map(SnapshotResponse::from).toList();
    }

    @GetMapping("/snapshots/{snapshotId}")
    public SnapshotResponse get(@PathVariable UUID snapshotId) {
        return SnapshotResponse.from(service.get(snapshotId));
    }
}
