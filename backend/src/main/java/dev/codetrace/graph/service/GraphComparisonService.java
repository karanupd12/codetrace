package dev.codetrace.graph.service;

import static dev.codetrace.graph.service.GraphQueryParams.ALL_STATES;
import static dev.codetrace.graph.service.GraphQueryParams.ALL_TYPES;
import static dev.codetrace.graph.service.GraphQueryParams.NODE_TYPES;
import static dev.codetrace.graph.service.GraphQueryParams.set;

import dev.codetrace.common.util.Bounds;
import dev.codetrace.common.web.ApiException;
import dev.codetrace.common.web.Page;
import dev.codetrace.config.GraphProperties;
import dev.codetrace.graph.model.EdgeChange;
import dev.codetrace.graph.model.NodeChange;
import dev.codetrace.graph.model.Snapshot;
import dev.codetrace.graph.model.SnapshotComparison;
import dev.codetrace.graph.repository.GraphComparisonRepository;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Compares the graphs of two published snapshots of the same repository. */
@Service
public class GraphComparisonService {
    private static final Set<String> NODE_CHANGES = Set.of("ADDED", "REMOVED", "CHANGED");
    private static final Set<String> EDGE_CHANGES = Set.of("ADDED", "REMOVED");

    private final GraphSnapshotService snapshots;
    private final GraphComparisonRepository comparisons;
    private final GraphProperties props;

    public GraphComparisonService(GraphSnapshotService snapshots, GraphComparisonRepository comparisons,
                                  GraphProperties props) {
        this.snapshots = snapshots;
        this.comparisons = comparisons;
        this.props = props;
    }

    public SnapshotComparison compare(UUID base, UUID head) {
        Snapshot[] pair = pair(base, head);
        return new SnapshotComparison(pair[0], pair[1], comparisons.nodeCounts(base, head), comparisons.edgeCounts(base, head));
    }

    public Page<NodeChange> nodes(UUID base, UUID head, String change, String type, int limit, int offset) {
        pair(base, head);
        return comparisons.nodes(base, head, choice(change, NODE_CHANGES, "change"), choice(type, NODE_TYPES, "type"),
                Bounds.limit(limit, props.maxQueryResults()), Bounds.offset(offset));
    }

    public Page<EdgeChange> edges(UUID base, UUID head, String change, String types, String states, int limit, int offset) {
        pair(base, head);
        return comparisons.edges(base, head, choice(change, EDGE_CHANGES, "change"), set(types, ALL_TYPES, ALL_TYPES, "types"),
                set(states, ALL_STATES, ALL_STATES, "states"), Bounds.limit(limit, props.maxQueryResults()),
                Bounds.offset(offset));
    }

    /** Both snapshots, published and of one repository; otherwise the comparison is refused. */
    private Snapshot[] pair(UUID base, UUID head) {
        Snapshot b = snapshots.getPublished(base);
        Snapshot h = snapshots.getPublished(head);
        if (!b.repositoryId().equals(h.repositoryId())) {
            throw ApiException.badRequest("Both snapshots must belong to the same repository.");
        }
        return new Snapshot[] {b, h};
    }

    private static String choice(String value, Set<String> allowed, String name) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            throw ApiException.badRequest(name + " must be one of " + String.join(", ", new TreeSet<>(allowed)) + ".");
        }
        return v;
    }
}
