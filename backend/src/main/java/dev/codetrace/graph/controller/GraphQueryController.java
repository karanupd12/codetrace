package dev.codetrace.graph.controller;

import dev.codetrace.common.web.Page;
import dev.codetrace.graph.model.EdgeView;
import dev.codetrace.graph.model.NodeDetail;
import dev.codetrace.graph.model.NodeView;
import dev.codetrace.graph.model.PathResult;
import dev.codetrace.graph.model.Subgraph;
import dev.codetrace.graph.service.GraphQueryService;
import dev.codetrace.graph.service.GraphTraversalService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reading a published graph snapshot: node search, node detail and edges, neighbourhoods and shortest paths. */
@RestController
@Profile("api")
@RequestMapping("/api/v1/snapshots/{snapshotId}")
public class GraphQueryController {
    private final GraphQueryService queries;
    private final GraphTraversalService traversal;

    public GraphQueryController(GraphQueryService queries, GraphTraversalService traversal) {
        this.queries = queries;
        this.traversal = traversal;
    }

    @GetMapping("/nodes")
    public Page<NodeView> nodes(@PathVariable UUID snapshotId,
                                @RequestParam(required = false) String type,
                                @RequestParam(required = false) String q,
                                @RequestParam(required = false) String path,
                                @RequestParam(defaultValue = "50") int limit,
                                @RequestParam(defaultValue = "0") int offset) {
        return queries.nodes(snapshotId, type, q, path, limit, offset);
    }

    @GetMapping("/nodes/{nodeId}")
    public NodeDetail node(@PathVariable UUID snapshotId, @PathVariable UUID nodeId) {
        return queries.node(snapshotId, nodeId);
    }

    @GetMapping("/nodes/{nodeId}/edges")
    public Page<EdgeView> edges(@PathVariable UUID snapshotId, @PathVariable UUID nodeId,
                                @RequestParam(required = false) String direction,
                                @RequestParam(required = false) String types,
                                @RequestParam(required = false) String states,
                                @RequestParam(defaultValue = "100") int limit,
                                @RequestParam(defaultValue = "0") int offset) {
        return queries.edges(snapshotId, nodeId, direction, types, states, limit, offset);
    }

    /** Neighbourhood subgraph for visualization. */
    @GetMapping("/graph")
    public Subgraph graph(@PathVariable UUID snapshotId, @RequestParam UUID center,
                          @RequestParam(defaultValue = "1") int depth,
                          @RequestParam(required = false) String direction,
                          @RequestParam(required = false) String types,
                          @RequestParam(required = false) String states,
                          @RequestParam(defaultValue = "200") int limit) {
        return traversal.neighborhood(snapshotId, center, depth, direction, types, states, limit);
    }

    @GetMapping("/paths")
    public PathResult paths(@PathVariable UUID snapshotId, @RequestParam UUID from, @RequestParam UUID to,
                            @RequestParam(defaultValue = "6") int maxDepth,
                            @RequestParam(defaultValue = "5") int limit,
                            @RequestParam(required = false) String direction,
                            @RequestParam(required = false) String types,
                            @RequestParam(required = false) String states) {
        return traversal.paths(snapshotId, from, to, maxDepth, limit, direction, types, states);
    }
}
