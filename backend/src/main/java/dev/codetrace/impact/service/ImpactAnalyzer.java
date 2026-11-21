package dev.codetrace.impact.service;

import dev.codetrace.common.util.Bounds;
import dev.codetrace.common.util.JavaSignatures;
import dev.codetrace.common.util.SourcePaths;
import dev.codetrace.impact.engine.BlastRadiusCalculator;
import dev.codetrace.impact.model.ImpactAnalysis;
import dev.codetrace.impact.change.ChangeSet;
import dev.codetrace.impact.change.ChangeSet.FileChange;
import dev.codetrace.impact.change.ChangedSymbolMapper;
import dev.codetrace.impact.change.ChangedSymbolMapper.ChangedSymbol;
import dev.codetrace.impact.change.ChangedSymbolMapper.Kind;
import dev.codetrace.impact.change.ChangedSymbolMapper.Mapping;
import dev.codetrace.impact.change.SymbolRef;
import dev.codetrace.config.ImpactProperties;
import dev.codetrace.graph.service.GraphQueryService;
import dev.codetrace.graph.model.EdgeView;
import dev.codetrace.graph.model.NodeView;
import dev.codetrace.impact.engine.BlastRadiusCalculator.Affected;
import dev.codetrace.impact.engine.BlastRadiusCalculator.Result;
import dev.codetrace.impact.model.ImpactAnalysis.AffectedSymbol;
import dev.codetrace.impact.model.ImpactAnalysis.ChangedEntry;
import dev.codetrace.impact.model.ImpactAnalysis.Hop;
import dev.codetrace.impact.model.ImpactAnalysis.PossibleDependent;
import dev.codetrace.graph.model.UnresolvedCall;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Computes the impact of a change against two published snapshots. Dependents are looked up in the <em>base</em>
 * snapshot, because it is the code that relied on the old behaviour that can break; symbols the change adds have no
 * dependents yet. The result is a plain value: scoring and rendering happen elsewhere.
 */
@Component
public class ImpactAnalyzer {
    public static final String ALGORITHM_VERSION = "impact-2"; // 2: edits to comments and whitespace no longer mark symbols as changed
    private static final int MAX_POSSIBLE = 200;

    private final ChangedSymbolMapper mapper;
    private final GraphQueryService graph;
    private final ImpactProperties limits;
    private final BlastRadiusCalculator calculator = new BlastRadiusCalculator();

    public ImpactAnalyzer(ChangedSymbolMapper mapper, GraphQueryService graph, ImpactProperties limits) {
        this.mapper = mapper;
        this.graph = graph;
        this.limits = limits;
    }

    public ImpactAnalysis analyze(UUID baseSnapshot, UUID headSnapshot, ChangeSet changes, Integer requestedDepth) {
        int depth = clampDepth(requestedDepth);

        Set<String> oldPaths = new TreeSet<>();
        Set<String> newPaths = new TreeSet<>();
        for (FileChange f : changes.javaFiles()) {
            if (f.oldPath() != null) oldPaths.add(f.oldPath());
            if (f.newPath() != null) newPaths.add(f.newPath());
        }
        Mapping mapping = mapper.map(changes, declarationsByPath(baseSnapshot, oldPaths), declarationsByPath(headSnapshot, newPaths));

        // seeds: symbols that existed before the change and were modified or removed
        Map<UUID, ChangedSymbol> seeds = new LinkedHashMap<>();
        for (ChangedSymbol c : mapping.symbols()) {
            if (c.base() != null && c.kind() != Kind.ADDED) seeds.put(c.base().nodeId(), c);
        }

        int edgeCap = limits.maxAffected() * 10;
        Result radius = calculator.calculate(new LinkedHashSet<>(seeds.keySet()), depth, limits.maxAffected(),
                frontier -> graph.dependentsOf(baseSnapshot, frontier, edgeCap),
                edgeCap);

        Map<UUID, Integer> direct = graph.directDependentCounts(baseSnapshot, seeds.keySet());
        List<ChangedEntry> changed = mapping.symbols().stream()
                .map(c -> new ChangedEntry(c.kind(), c.base(), c.head(), c.base() == null ? 0 : direct.getOrDefault(c.base().nodeId(), 0)))
                .toList();

        List<AffectedSymbol> affected = describe(baseSnapshot, radius.affected(), seeds);
        List<PossibleDependent> possible = possibleDependents(baseSnapshot, seeds, affected);

        return new ImpactAnalysis(ALGORITHM_VERSION, depth, changed, affected, possible, mapping.unmappedHunks(),
                changes.nonJavaFileCount(), radius.truncated());
    }

    /** The declarations of the given files in a snapshot, grouped by file. */
    private Map<String, List<SymbolRef>> declarationsByPath(UUID snapshot, Set<String> paths) {
        Map<String, List<SymbolRef>> out = new LinkedHashMap<>();
        for (NodeView n : graph.declarationsIn(snapshot, paths)) {
            out.computeIfAbsent(n.path(), k -> new ArrayList<>()).add(new SymbolRef(n.id(), n.nodeType(), n.qualifiedName(),
                    n.signature(), n.path(), n.startLine(), n.endLine(), n.modifiers()));
        }
        return out;
    }

    private int clampDepth(Integer requested) {
        int d = requested == null ? limits.defaultDepth() : requested;
        return Bounds.clamp(d, 1, limits.maxDepth());
    }

    private List<AffectedSymbol> describe(UUID snapshot, List<Affected> found, Map<UUID, ChangedSymbol> seeds) {
        Map<UUID, NodeView> nodes = new LinkedHashMap<>();
        graph.nodes(snapshot, found.stream().map(Affected::nodeId).toList()).forEach(n -> nodes.put(n.id(), n));
        List<AffectedSymbol> out = new ArrayList<>();
        for (Affected a : found) {
            NodeView n = nodes.get(a.nodeId());
            if (n == null) continue; // cannot happen under the snapshot's foreign keys; never invent a symbol
            ChangedSymbol seed = seeds.get(a.seedId());
            List<Hop> chain = a.path().stream().map(ImpactAnalyzer::hop).toList();
            out.add(new AffectedSymbol(n.id(), n.nodeType(), n.qualifiedName(), n.signature(), n.path(), n.startLine(),
                    SourcePaths.displayModule(n.path()), SourcePaths.isTestPath(n.path()), a.distance(), a.certain(),
                    a.confidence(), seed == null ? "" : seed.base().qualifiedName(), chain));
        }
        out.sort(Comparator.comparingInt(AffectedSymbol::distance).thenComparing(AffectedSymbol::qualifiedName)
                .thenComparing(AffectedSymbol::signature));
        return List.copyOf(out);
    }

    private static Hop hop(EdgeView e) {
        return new Hop(e.srcName(), e.dstName(), e.edgeType(), e.resolution(), e.confidence(), e.evidencePath(), e.evidenceLine());
    }

    /** Unknown-receiver calls whose method name matches a changed method: unproven, so reported separately. */
    private List<PossibleDependent> possibleDependents(UUID snapshot, Map<UUID, ChangedSymbol> seeds, List<AffectedSymbol> affected) {
        record ChangedMethod(String qualifiedName, int minArgs, int maxArgs) {
            boolean accepts(int argc) {
                return argc >= minArgs && argc <= maxArgs;
            }
        }
        Map<String, List<ChangedMethod>> changedByName = new LinkedHashMap<>();
        for (ChangedSymbol c : seeds.values()) {
            SymbolRef b = c.base();
            if (!"METHOD".equals(b.nodeType())) continue;
            // a method's qualified name already carries its parameter list: "p.Type#name(int,String)"
            int hash = b.qualifiedName().lastIndexOf('#');
            int paren = b.qualifiedName().indexOf('(', Math.max(hash, 0));
            String name = hash < 0 ? b.qualifiedName()
                    : b.qualifiedName().substring(hash + 1, paren < 0 ? b.qualifiedName().length() : paren);
            if (name.equals("<init>")) continue;
            int[] arity = JavaSignatures.arityRange(b.signature());
            changedByName.computeIfAbsent(name, k -> new ArrayList<>()).add(new ChangedMethod(b.qualifiedName(), arity[0], arity[1]));
        }
        Set<UUID> known = new LinkedHashSet<>(seeds.keySet());
        affected.forEach(a -> known.add(a.nodeId()));
        List<PossibleDependent> out = new ArrayList<>();
        for (UnresolvedCall u : graph.unknownReceiverCalls(snapshot, changedByName.keySet(), MAX_POSSIBLE)) {
            if (known.contains(u.callerId())) continue;
            changedByName.getOrDefault(u.methodName(), List.of()).stream().filter(m -> m.accepts(u.argCount())).findFirst()
                    .ifPresent(m -> out.add(new PossibleDependent(u.callerId(), u.callerName(), u.path(), u.line(), m.qualifiedName())));
        }
        return List.copyOf(out);
    }
}
