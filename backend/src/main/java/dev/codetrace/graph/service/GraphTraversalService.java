package dev.codetrace.graph.service;

import static dev.codetrace.graph.service.GraphQueryParams.ALL_STATES;
import static dev.codetrace.graph.service.GraphQueryParams.ALL_TYPES;
import static dev.codetrace.graph.service.GraphQueryParams.DEFAULT_TYPES;
import static dev.codetrace.graph.service.GraphQueryParams.TRAVERSABLE_STATES;
import static dev.codetrace.graph.service.GraphQueryParams.direction;
import static dev.codetrace.graph.service.GraphQueryParams.set;

import dev.codetrace.common.util.Bounds;
import dev.codetrace.common.web.ApiException;
import dev.codetrace.config.GraphProperties;
import dev.codetrace.graph.model.Direction;
import dev.codetrace.graph.model.EdgeView;
import dev.codetrace.graph.model.GraphPath;
import dev.codetrace.graph.model.PathResult;
import dev.codetrace.graph.model.Subgraph;
import dev.codetrace.graph.repository.GraphQueryRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Neighbourhoods and shortest paths over a published snapshot. Both are breadth-first with a visited set, so cycles
 * cannot loop, and both are capped by {@link GraphProperties}; when a cap cuts a result short it says {@code truncated}.
 */
@Service
public class GraphTraversalService {
    private final GraphSnapshotService snapshots;
    private final GraphQueryRepository dao;
    private final GraphProperties props;

    public GraphTraversalService(GraphSnapshotService snapshots, GraphQueryRepository dao, GraphProperties props) {
        this.snapshots = snapshots;
        this.dao = dao;
        this.props = props;
    }

    /** Breadth-first subgraph around {@code center}, at most {@code depth} hops and {@code maxQueryResults} edges. */
    public Subgraph neighborhood(UUID snapshot, UUID center, int depth, String direction, String types, String states, int limit) {
        snapshots.getPublished(snapshot);
        requireNode(snapshot, center);
        int maxDepth = Bounds.clamp(depth, 1, props.maxDepth());
        int maxEdges = Bounds.limit(limit, props.maxQueryResults());
        Direction dir = direction(direction, Direction.BOTH);
        Set<String> typeSet = set(types, DEFAULT_TYPES, ALL_TYPES, "types");
        Set<String> stateSet = set(states, ALL_STATES, ALL_STATES, "states");

        Set<UUID> seen = new LinkedHashSet<>(List.of(center));
        Map<Long, EdgeView> edges = new LinkedHashMap<>();
        Set<UUID> frontier = new LinkedHashSet<>(List.of(center));
        boolean truncated = false;
        for (int level = 1; level <= maxDepth && !frontier.isEmpty() && !truncated; level++) {
            List<EdgeView> found = dao.touching(snapshot, frontier, dir, typeSet, stateSet, false, maxEdges - edges.size() + 1);
            Set<UUID> next = new LinkedHashSet<>();
            for (EdgeView e : found) {
                if (edges.containsKey(e.id())) continue;
                if (edges.size() >= maxEdges) {
                    truncated = true;
                    break;
                }
                edges.put(e.id(), e);
                if (seen.add(e.srcId())) next.add(e.srcId());
                if (e.dstId() != null && seen.add(e.dstId())) next.add(e.dstId());
            }
            frontier = next;
        }
        return new Subgraph(center, maxDepth, dao.nodesByIds(snapshot, seen), new ArrayList<>(edges.values()), truncated);
    }

    /**
     * Shortest paths between two nodes. Ambiguous edges are traversable but mark the path as not {@code certain}.
     * Direction IN follows edges backwards (who depends on {@code from}). Stops at {@code maxDepth} hops or
     * {@code maxPathVisited} nodes.
     */
    public PathResult paths(UUID snapshot, UUID from, UUID to, int maxDepthParam, int limitPaths, String direction,
                            String types, String states) {
        snapshots.getPublished(snapshot);
        requireNode(snapshot, from);
        requireNode(snapshot, to);
        int maxDepth = Bounds.clamp(maxDepthParam, 1, props.maxPathDepth());
        int wanted = Bounds.limit(limitPaths, 20);
        Direction dir = direction(direction, Direction.OUT);
        if (dir == Direction.BOTH) throw ApiException.badRequest("direction must be OUT or IN for path queries.");
        Set<String> typeSet = set(types, DEFAULT_TYPES, ALL_TYPES, "types");
        Set<String> stateSet = set(states, TRAVERSABLE_STATES, TRAVERSABLE_STATES, "states");

        if (from.equals(to)) {
            return new PathResult(from, to, true, 0, List.of(new GraphPath(0, 1.0, true, List.of())), false, maxDepth);
        }

        Map<UUID, Integer> distance = new HashMap<>(Map.of(from, 0));
        Map<UUID, List<EdgeView>> predecessors = new HashMap<>();
        Set<UUID> frontier = new LinkedHashSet<>(List.of(from));
        boolean truncated = false;
        int perLevelCap = props.maxPathVisited() * 5;

        for (int depth = 1; depth <= maxDepth && !frontier.isEmpty(); depth++) {
            List<EdgeView> found = dao.touching(snapshot, frontier, dir, typeSet, stateSet, true, perLevelCap + 1);
            if (found.size() > perLevelCap) {
                truncated = true;
                found = found.subList(0, perLevelCap);
            }
            Set<UUID> next = new LinkedHashSet<>();
            for (EdgeView e : found) {
                UUID other = dir == Direction.OUT ? e.dstId() : e.srcId();
                Integer known = distance.get(other);
                if (known == null) {
                    distance.put(other, depth);
                    predecessors.computeIfAbsent(other, k -> new ArrayList<>()).add(e);
                    next.add(other);
                } else if (known == depth) {
                    predecessors.get(other).add(e); // another shortest way to reach the same node
                }
            }
            if (distance.containsKey(to)) break;
            if (distance.size() > props.maxPathVisited()) {
                truncated = true;
                break;
            }
            frontier = next;
        }

        if (!distance.containsKey(to)) {
            return new PathResult(from, to, false, null, List.of(), truncated, maxDepth);
        }
        List<GraphPath> paths = new ArrayList<>();
        collect(to, from, dir, predecessors, new ArrayList<>(), paths, wanted);
        return new PathResult(from, to, true, distance.get(to), paths, truncated, maxDepth);
    }

    private void collect(UUID node, UUID from, Direction dir, Map<UUID, List<EdgeView>> preds, List<EdgeView> suffix,
                         List<GraphPath> out, int wanted) {
        if (out.size() >= wanted) return;
        if (node.equals(from)) {
            List<EdgeView> ordered = new ArrayList<>(suffix);
            Collections.reverse(ordered);
            double min = ordered.stream().mapToDouble(EdgeView::confidence).min().orElse(1.0);
            boolean certain = ordered.stream().allMatch(e -> "RESOLVED".equals(e.resolution()));
            out.add(new GraphPath(ordered.size(), min, certain, ordered));
            return;
        }
        for (EdgeView e : preds.getOrDefault(node, List.of())) {
            suffix.add(e);
            collect(dir == Direction.OUT ? e.srcId() : e.dstId(), from, dir, preds, suffix, out, wanted);
            suffix.remove(suffix.size() - 1);
            if (out.size() >= wanted) return;
        }
    }

    private void requireNode(UUID snapshot, UUID nodeId) {
        dao.findNode(snapshot, nodeId).orElseThrow(() -> ApiException.notFound("Node not found in this snapshot."));
    }
}
