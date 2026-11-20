package dev.codetrace.graph;

import dev.codetrace.graph.GraphQueryDao.EdgeView;
import dev.codetrace.graph.GraphQueryDao.NodeView;
import dev.codetrace.system.Page;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads the graph of a PUBLISHED snapshot. Any other snapshot state answers 409; unknown ids 404. Results are bounded and
 * every edge includes its provenance (evidence, confidence, resolution state, resolver version).
 */
@RestController
@Profile("api")
@RequestMapping("/api/v1/snapshots/{snapshotId}")
public class GraphQueryController {
    private final GraphService snapshots;
    private final GraphQueryService queries;

    public GraphQueryController(GraphService snapshots, GraphQueryService queries) {
        this.snapshots = snapshots;
        this.queries = queries;
    }

    @GetMapping("/nodes")
    public Page<NodeView> nodes(@PathVariable UUID snapshotId,
                                @RequestParam(required = false) String type,
                                @RequestParam(required = false) String q,
                                @RequestParam(required = false) String path,
                                @RequestParam(defaultValue = "50") int limit,
                                @RequestParam(defaultValue = "0") int offset) {
        snapshots.getPublished(snapshotId);
        return queries.nodes(snapshotId, type, q, path, limit, offset);
    }

    @GetMapping("/nodes/{nodeId}")
    public GraphQueryService.NodeDetail node(@PathVariable UUID snapshotId, @PathVariable UUID nodeId) {
        snapshots.getPublished(snapshotId);
        return queries.node(snapshotId, nodeId);
    }

    @GetMapping("/nodes/{nodeId}/edges")
    public Page<EdgeView> edges(@PathVariable UUID snapshotId, @PathVariable UUID nodeId,
                                @RequestParam(required = false) String direction,
                                @RequestParam(required = false) String types,
                                @RequestParam(required = false) String states,
                                @RequestParam(defaultValue = "100") int limit,
                                @RequestParam(defaultValue = "0") int offset) {
        snapshots.getPublished(snapshotId);
        return queries.edges(snapshotId, nodeId, direction, types, states, limit, offset);
    }

    /** Neighbourhood subgraph for visualization. */
    @GetMapping("/graph")
    public GraphQueryService.Subgraph graph(@PathVariable UUID snapshotId, @RequestParam UUID center,
                                            @RequestParam(defaultValue = "1") int depth,
                                            @RequestParam(required = false) String direction,
                                            @RequestParam(required = false) String types,
                                            @RequestParam(required = false) String states,
                                            @RequestParam(defaultValue = "200") int limit) {
        snapshots.getPublished(snapshotId);
        return queries.neighborhood(snapshotId, center, depth, direction, types, states, limit);
    }

    @GetMapping("/paths")
    public GraphQueryService.PathResult paths(@PathVariable UUID snapshotId, @RequestParam UUID from, @RequestParam UUID to,
                                              @RequestParam(defaultValue = "6") int maxDepth,
                                              @RequestParam(defaultValue = "5") int limit,
                                              @RequestParam(required = false) String direction,
                                              @RequestParam(required = false) String types,
                                              @RequestParam(required = false) String states) {
        snapshots.getPublished(snapshotId);
        return queries.paths(snapshotId, from, to, maxDepth, limit, direction, types, states);
    }
}
