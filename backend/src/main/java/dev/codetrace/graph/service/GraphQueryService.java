package dev.codetrace.graph.service;

import static dev.codetrace.graph.service.GraphQueryParams.ALL_STATES;
import static dev.codetrace.graph.service.GraphQueryParams.ALL_TYPES;
import static dev.codetrace.graph.service.GraphQueryParams.NODE_TYPES;
import static dev.codetrace.graph.service.GraphQueryParams.TRAVERSABLE_STATES;
import static dev.codetrace.graph.service.GraphQueryParams.blankToNull;
import static dev.codetrace.graph.service.GraphQueryParams.direction;
import static dev.codetrace.graph.service.GraphQueryParams.set;

import dev.codetrace.common.util.Bounds;
import dev.codetrace.common.web.ApiException;
import dev.codetrace.common.web.Page;
import dev.codetrace.config.GraphProperties;
import dev.codetrace.graph.model.Direction;
import dev.codetrace.graph.model.EdgeView;
import dev.codetrace.graph.model.NodeDetail;
import dev.codetrace.graph.model.NodeView;
import dev.codetrace.graph.model.UnresolvedCall;
import dev.codetrace.graph.repository.GraphQueryRepository;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Bounded reads over a snapshot. The API-facing methods only answer for published snapshots; the methods used by other
 * features (impact analysis) take a snapshot id their caller already resolved to a published snapshot.
 */
@Service
public class GraphQueryService {
    /** Edge types that make one symbol depend on another; containment and imports are structure, not dependency. */
    public static final Set<String> DEPENDENCY_TYPES = Set.of("CALLS", "REFERENCES", "EXTENDS", "IMPLEMENTS");

    private final GraphSnapshotService snapshots;
    private final GraphQueryRepository dao;
    private final GraphProperties props;

    public GraphQueryService(GraphSnapshotService snapshots, GraphQueryRepository dao, GraphProperties props) {
        this.snapshots = snapshots;
        this.dao = dao;
        this.props = props;
    }

    // ---- API reads --------------------------------------------------------------------------------------------------

    public Page<NodeView> nodes(UUID snapshot, String type, String q, String path, int limit, int offset) {
        snapshots.getPublished(snapshot);
        if (type != null && !NODE_TYPES.contains(type.toUpperCase(Locale.ROOT))) {
            throw ApiException.badRequest("type must be one of " + String.join(", ", new TreeSet<>(NODE_TYPES)) + ".");
        }
        return dao.searchNodes(snapshot, type == null ? null : type.toUpperCase(Locale.ROOT), blankToNull(q),
                blankToNull(path), Bounds.limit(limit, props.maxQueryResults()), Bounds.offset(offset));
    }

    public NodeDetail node(UUID snapshot, UUID nodeId) {
        snapshots.getPublished(snapshot);
        NodeView n = requireNode(snapshot, nodeId);
        return new NodeDetail(n, dao.degrees(snapshot, nodeId, Direction.OUT), dao.degrees(snapshot, nodeId, Direction.IN));
    }

    public Page<EdgeView> edges(UUID snapshot, UUID nodeId, String direction, String types, String states, int limit, int offset) {
        snapshots.getPublished(snapshot);
        requireNode(snapshot, nodeId);
        return dao.edgesOf(snapshot, nodeId, direction(direction, Direction.BOTH), set(types, ALL_TYPES, ALL_TYPES, "types"),
                set(states, ALL_STATES, ALL_STATES, "states"), Bounds.limit(limit, props.maxQueryResults()),
                Bounds.offset(offset));
    }

    // ---- reads for impact analysis ----------------------------------------------------------------------------------

    /** Types, methods and fields declared in the given files. */
    public List<NodeView> declarationsIn(UUID snapshot, Collection<String> paths) {
        return dao.declarationsIn(snapshot, paths);
    }

    public List<NodeView> nodes(UUID snapshot, Collection<UUID> ids) {
        return dao.nodesByIds(snapshot, ids);
    }

    /** Resolved or ambiguous dependency edges into any of {@code targets}, ordered by edge id, at most {@code limit}. */
    public List<EdgeView> dependentsOf(UUID snapshot, Collection<UUID> targets, int limit) {
        return dao.touching(snapshot, targets, Direction.IN, DEPENDENCY_TYPES, TRAVERSABLE_STATES, true, limit);
    }

    /** For each target, how many distinct nodes depend on it directly. */
    public Map<UUID, Integer> directDependentCounts(UUID snapshot, Collection<UUID> targets) {
        return dao.dependentCounts(snapshot, targets, DEPENDENCY_TYPES);
    }

    /** Calls the resolver could not attribute to a receiver type, for the given method names. */
    public List<UnresolvedCall> unknownReceiverCalls(UUID snapshot, Collection<String> methodNames, int limit) {
        return dao.unknownReceiverCalls(snapshot, methodNames, limit);
    }

    private NodeView requireNode(UUID snapshot, UUID nodeId) {
        return dao.findNode(snapshot, nodeId).orElseThrow(() -> ApiException.notFound("Node not found in this snapshot."));
    }
}
