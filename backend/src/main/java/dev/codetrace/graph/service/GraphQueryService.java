package dev.codetrace.graph.service;

import dev.codetrace.common.util.Bounds;
import dev.codetrace.graph.repository.GraphQueryRepository;
import dev.codetrace.config.GraphProperties;
import dev.codetrace.graph.repository.GraphQueryRepository.Degree;
import dev.codetrace.graph.repository.GraphQueryRepository.Direction;
import dev.codetrace.graph.repository.GraphQueryRepository.EdgeView;
import dev.codetrace.graph.repository.GraphQueryRepository.NodeView;
import dev.codetrace.common.web.ApiException;
import dev.codetrace.common.web.Page;
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
 * Bounded, cycle-safe reads over a published snapshot: node search, a node's edges, neighbourhood subgraphs for
 * visualization, and shortest paths. Every traversal tracks visited nodes and is capped by {@link GraphProperties}; when a
 * cap cuts a result short the response says {@code truncated} instead of silently returning less.
 */
@Service
public class GraphQueryService {
    public static final Set<String> DEFAULT_TYPES = Set.of("CALLS", "REFERENCES", "EXTENDS", "IMPLEMENTS", "IMPORTS");
    public static final Set<String> ALL_TYPES = Set.of("CONTAINS", "CALLS", "REFERENCES", "EXTENDS", "IMPLEMENTS", "IMPORTS");
    public static final Set<String> ALL_STATES = Set.of("RESOLVED", "AMBIGUOUS", "UNRESOLVED");
    public static final Set<String> TRAVERSABLE_STATES = Set.of("RESOLVED", "AMBIGUOUS");
    private static final Set<String> NODE_TYPES = Set.of("FILE", "PACKAGE", "TYPE", "METHOD", "FIELD");

    public record NodeDetail(NodeView node, List<Degree> outgoing, List<Degree> incoming) {
    }

    public record Subgraph(UUID center, int depth, List<NodeView> nodes, List<EdgeView> edges, boolean truncated) {
    }

    /** One shortest path. {@code certain} is true only if every hop is RESOLVED. */
    public record Path(int length, double minConfidence, boolean certain, List<EdgeView> edges) {
    }

    public record PathResult(UUID from, UUID to, boolean found, Integer shortestLength, List<Path> paths,
                             boolean truncated, int maxDepth) {
    }

    private final GraphQueryRepository dao;
    private final GraphProperties props;

    public GraphQueryService(GraphQueryRepository dao, GraphProperties props) {
        this.dao = dao;
        this.props = props;
    }

    // ---- nodes and edges ----------------------------------------------------------------------------------------------

    public Page<NodeView> nodes(UUID snapshot, String type, String q, String path, int limit, int offset) {
        if (type != null && !NODE_TYPES.contains(type.toUpperCase())) {
            throw ApiException.badRequest("type must be one of " + String.join(", ", new java.util.TreeSet<>(NODE_TYPES)) + ".");
        }
        return dao.searchNodes(snapshot, type == null ? null : type.toUpperCase(), blankToNull(q), blankToNull(path),
                clampLimit(limit), Bounds.offset(offset));
    }

    public NodeDetail node(UUID snapshot, UUID nodeId) {
        NodeView n = requireNode(snapshot, nodeId);
        return new NodeDetail(n, dao.degrees(snapshot, nodeId, Direction.OUT), dao.degrees(snapshot, nodeId, Direction.IN));
    }

    public Page<EdgeView> edges(UUID snapshot, UUID nodeId, String direction, String types, String states, int limit, int offset) {
        requireNode(snapshot, nodeId);
        return dao.edgesOf(snapshot, nodeId, direction(direction, Direction.BOTH), parseSet(types, ALL_TYPES, ALL_TYPES, "types"),
                parseSet(states, ALL_STATES, ALL_STATES, "states"), clampLimit(limit), Bounds.offset(offset));
    }

    // ---- neighbourhood ------------------------------------------------------------------------------------------------

    /** Breadth-first subgraph around {@code center}, at most {@code depth} hops and {@code maxQueryResults} edges. */
    public Subgraph neighborhood(UUID snapshot, UUID center, int depth, String direction, String types, String states, int limit) {
        requireNode(snapshot, center);
        int maxDepth = Bounds.clamp(depth, 1, props.maxDepth());
        int maxEdges = clampLimit(limit);
        Direction dir = direction(direction, Direction.BOTH);
        Set<String> typeSet = parseSet(types, DEFAULT_TYPES, ALL_TYPES, "types");
        Set<String> stateSet = parseSet(states, ALL_STATES, ALL_STATES, "states");

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

    // ---- paths --------------------------------------------------------------------------------------------------------

    /**
     * Shortest paths between two nodes by breadth-first search with a visited set, so cycles cannot loop. Ambiguous edges
     * are traversable but mark the path as not {@code certain}. Direction IN follows edges backwards (who depends on
     * {@code from}). Stops at {@code maxDepth} hops or {@code maxPathVisited} nodes.
     */
    public PathResult paths(UUID snapshot, UUID from, UUID to, int maxDepthParam, int limitPaths, String direction,
                            String types, String states) {
        requireNode(snapshot, from);
        requireNode(snapshot, to);
        int maxDepth = Bounds.clamp(maxDepthParam, 1, props.maxPathDepth());
        int wanted = Bounds.limit(limitPaths, 20);
        Direction dir = direction(direction, Direction.OUT);
        if (dir == Direction.BOTH) throw ApiException.badRequest("direction must be OUT or IN for path queries.");
        Set<String> typeSet = parseSet(types, DEFAULT_TYPES, ALL_TYPES, "types");
        Set<String> stateSet = parseSet(states, TRAVERSABLE_STATES, TRAVERSABLE_STATES, "states");

        if (from.equals(to)) {
            return new PathResult(from, to, true, 0, List.of(new Path(0, 1.0, true, List.of())), false, maxDepth);
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
        List<Path> paths = new ArrayList<>();
        collect(to, from, dir, predecessors, new ArrayList<>(), paths, wanted);
        return new PathResult(from, to, true, distance.get(to), paths, truncated, maxDepth);
    }

    private void collect(UUID node, UUID from, Direction dir, Map<UUID, List<EdgeView>> preds, List<EdgeView> suffix,
                         List<Path> out, int wanted) {
        if (out.size() >= wanted) return;
        if (node.equals(from)) {
            List<EdgeView> ordered = new ArrayList<>(suffix);
            Collections.reverse(ordered);
            double min = ordered.stream().mapToDouble(EdgeView::confidence).min().orElse(1.0);
            boolean certain = ordered.stream().allMatch(e -> "RESOLVED".equals(e.resolution()));
            out.add(new Path(ordered.size(), min, certain, ordered));
            return;
        }
        for (EdgeView e : preds.getOrDefault(node, List.of())) {
            suffix.add(e);
            collect(dir == Direction.OUT ? e.srcId() : e.dstId(), from, dir, preds, suffix, out, wanted);
            suffix.remove(suffix.size() - 1);
            if (out.size() >= wanted) return;
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private NodeView requireNode(UUID snapshot, UUID nodeId) {
        return dao.findNode(snapshot, nodeId).orElseThrow(() -> ApiException.notFound("Node not found in this snapshot."));
    }

    private int clampLimit(int limit) {
        return Bounds.limit(limit, props.maxQueryResults());
    }

    private static Direction direction(String value, Direction fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Direction.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("direction must be OUT, IN or BOTH.");
        }
    }

    private static Set<String> parseSet(String csv, Set<String> fallback, Set<String> allowed, String name) {
        if (csv == null || csv.isBlank()) return fallback;
        Set<String> out = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String v = part.trim().toUpperCase();
            if (v.isEmpty()) continue;
            if (!allowed.contains(v)) {
                throw ApiException.badRequest(name + " may contain only " + String.join(", ", new java.util.TreeSet<>(allowed)) + ".");
            }
            out.add(v);
        }
        return out.isEmpty() ? fallback : out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
