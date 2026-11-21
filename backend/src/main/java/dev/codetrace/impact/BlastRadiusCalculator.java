package dev.codetrace.impact;

import dev.codetrace.graph.GraphQueryDao.EdgeView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Finds everything that depends, directly or transitively, on a set of changed symbols: a multi-source breadth-first
 * search that follows dependency edges <em>backwards</em>. A visited set makes cycles harmless, the depth and result
 * caps bound the work, and every affected symbol keeps the shortest chain of edges that links it to the change, so a
 * finding can always show its evidence.
 *
 * <p>A chain is {@code certain} only if every edge on it is {@code RESOLVED}; ambiguous hops are followed (dropping them
 * would hide real dependencies) but mark the chain as uncertain. Where a symbol is reachable by several chains of the
 * same length, the more certain and more confident one is kept, ties broken by edge id so results are deterministic.
 */
public class BlastRadiusCalculator {

    /** {@code path} runs from the affected symbol towards the seed: first edge starts at the affected symbol. */
    public record Affected(UUID nodeId, int distance, UUID seedId, boolean certain, double confidence, List<EdgeView> path) {
    }

    public record Result(List<Affected> affected, boolean truncated) {
    }

    /**
     * @param seeds        nodes that changed (they are not reported as affected)
     * @param maxDepth     hops to follow, at least 1
     * @param maxAffected  stop (and say so) once this many symbols are affected
     * @param dependentsOf edges whose destination is one of the given nodes, ordered by edge id; may be capped by the
     *                     caller, which signals that cap by returning {@code edgeCap} edges
     * @param edgeCap      the number of edges {@code dependentsOf} returns at most per call
     */
    public Result calculate(Set<UUID> seeds, int maxDepth, int maxAffected,
                            Function<Collection<UUID>, List<EdgeView>> dependentsOf, int edgeCap) {
        Map<UUID, Affected> found = new LinkedHashMap<>();
        Set<UUID> visited = new HashSet<>(seeds);
        Set<UUID> frontier = new LinkedHashSet<>(seeds);
        Map<UUID, UUID> seedOf = new HashMap<>();
        seeds.forEach(s -> seedOf.put(s, s));
        boolean truncated = false;

        for (int depth = 1; depth <= Math.max(1, maxDepth) && !frontier.isEmpty() && !truncated; depth++) {
            List<EdgeView> edges = dependentsOf.apply(frontier);
            if (edges.size() >= edgeCap) truncated = true;

            Map<UUID, Affected> level = new LinkedHashMap<>();
            for (EdgeView e : edges) {
                UUID dependent = e.srcId();
                UUID target = e.dstId();
                if (target == null || visited.contains(dependent)) continue; // changed already, or found nearer
                Affected parent = found.get(target);
                List<EdgeView> path = new ArrayList<>();
                path.add(e);
                boolean parentCertain = true;
                double parentConfidence = 1.0;
                UUID seed = seedOf.get(target);
                if (parent != null) {
                    path.addAll(parent.path());
                    parentCertain = parent.certain();
                    parentConfidence = parent.confidence();
                    seed = parent.seedId();
                }
                boolean certain = parentCertain && "RESOLVED".equals(e.resolution());
                double confidence = Math.min(parentConfidence, e.confidence());
                Affected candidate = new Affected(dependent, depth, seed, certain, confidence, List.copyOf(path));
                Affected current = level.get(dependent);
                if (current == null || better(candidate, current)) level.put(dependent, candidate);
            }

            Set<UUID> next = new LinkedHashSet<>();
            for (Affected a : level.values()) {
                if (found.size() >= maxAffected) {
                    truncated = true;
                    break;
                }
                found.put(a.nodeId(), a);
                visited.add(a.nodeId());
                seedOf.put(a.nodeId(), a.seedId());
                next.add(a.nodeId());
            }
            frontier = next;
        }
        List<Affected> out = new ArrayList<>(found.values());
        return new Result(List.copyOf(out), truncated);
    }

    private static boolean better(Affected a, Affected b) {
        if (a.certain() != b.certain()) return a.certain();
        if (Double.compare(a.confidence(), b.confidence()) != 0) return a.confidence() > b.confidence();
        return a.path().get(0).id() < b.path().get(0).id();
    }
}
