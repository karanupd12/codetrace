package dev.codetrace.graph.controller;

import dev.codetrace.common.web.Page;
import dev.codetrace.graph.controller.dto.ComparisonResponse;
import dev.codetrace.graph.model.EdgeChange;
import dev.codetrace.graph.model.NodeChange;
import dev.codetrace.graph.service.GraphComparisonService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What changed in the dependency graph between two published snapshots of one repository. */
@RestController
@Profile("api")
@RequestMapping("/api/v1/snapshots/{base}/compare/{head}")
public class GraphComparisonController {
    private final GraphComparisonService service;

    public GraphComparisonController(GraphComparisonService service) {
        this.service = service;
    }

    @GetMapping
    public ComparisonResponse summary(@PathVariable UUID base, @PathVariable UUID head) {
        return ComparisonResponse.from(service.compare(base, head));
    }

    @GetMapping("/nodes")
    public Page<NodeChange> nodes(@PathVariable UUID base, @PathVariable UUID head,
                                  @RequestParam(required = false) String change,
                                  @RequestParam(required = false) String type,
                                  @RequestParam(defaultValue = "50") int limit,
                                  @RequestParam(defaultValue = "0") int offset) {
        return service.nodes(base, head, change, type, limit, offset);
    }

    @GetMapping("/edges")
    public Page<EdgeChange> edges(@PathVariable UUID base, @PathVariable UUID head,
                                  @RequestParam(required = false) String change,
                                  @RequestParam(required = false) String types,
                                  @RequestParam(required = false) String states,
                                  @RequestParam(defaultValue = "50") int limit,
                                  @RequestParam(defaultValue = "0") int offset) {
        return service.edges(base, head, change, types, states, limit, offset);
    }
}
