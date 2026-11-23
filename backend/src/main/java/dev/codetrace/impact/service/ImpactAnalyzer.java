package dev.codetrace.impact.service;

import dev.codetrace.common.util.Bounds;
import dev.codetrace.common.util.JavaSignatures;
import dev.codetrace.common.util.SourcePaths;
import dev.codetrace.impact.engine.BlastRadiusCalculator;
import dev.codetrace.impact.engine.EndpointImpactCalculator;
import dev.codetrace.impact.model.ImpactAnalysis.EndpointImpact;
import dev.codetrace.graph.service.GraphSnapshotService;
import dev.codetrace.symbols.model.IndexedEndpoint;
import dev.codetrace.symbols.service.SymbolService;
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
    // 2: edits to comments and whitespace no longer mark symbols as changed; 3: test classes named after changed code;
    // 4: HTTP endpoints added, removed, modified or affected; 5: callers of overridden supertype methods (uncertain);
    // 6: only abstract and interface methods are followed to their callers; 7: not when widely implemented (noted)
    public static final String ALGORITHM_VERSION = "impact-7";
    private static final int MAX_POSSIBLE = 200;

    private final ChangedSymbolMapper mapper;
    private final GraphQueryService graph;
    private final ImpactProperties limits;
    private final GraphSnapshotService snapshots;
    private final SymbolService symbols;
    private final BlastRadiusCalculator calculator = new BlastRadiusCalculator();
    private final EndpointImpactCalculator endpointCalculator = new EndpointImpactCalculator();

    public ImpactAnalyzer(ChangedSymbolMapper mapper, GraphQueryService graph, ImpactProperties limits,
                          GraphSnapshotService snapshots, SymbolService symbols) {
        this.mapper = mapper;
        this.graph = graph;
        this.limits = limits;
        this.snapshots = snapshots;
        this.symbols = symbols;
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
        Map<String, Integer> notFollowed = new java.util.TreeMap<>();
        Result radius = calculator.calculateLevels(new LinkedHashSet<>(seeds.keySet()), depth, limits.maxAffected(),
                frontier -> {
                    List<EdgeView> direct = graph.dependentsOf(baseSnapshot, frontier, edgeCap);
                    List<EdgeView> dispatch = graph.dispatchDependentsOf(baseSnapshot, frontier,
                            limits.maxDispatchImplementations(), edgeCap);
                    notFollowed.putAll(graph.widelyImplemented(baseSnapshot, frontier, limits.maxDispatchImplementations()));
                    List<EdgeView> edges = new ArrayList<>(direct);
                    edges.addAll(dispatch);
                    edges.sort(Comparator.comparingLong(EdgeView::id));
                    return new BlastRadiusCalculator.Level(edges, direct.size() >= edgeCap || dispatch.size() >= edgeCap);
                });

        Map<UUID, Integer> direct = graph.directDependentCounts(baseSnapshot, seeds.keySet());
        List<ChangedEntry> changed = mapping.symbols().stream()
                .map(c -> new ChangedEntry(c.kind(), c.base(), c.head(), c.base() == null ? 0 : direct.getOrDefault(c.base().nodeId(), 0)))
                .toList();

        List<AffectedSymbol> affected = new ArrayList<>(describe(baseSnapshot, radius.affected(), seeds));
        boolean truncated = radius.truncated() | addNamedTests(baseSnapshot, seeds, affected);
        affected.sort(BY_DISTANCE);
        List<PossibleDependent> possible = possibleDependents(baseSnapshot, seeds, affected);
        Set<String> changedHandlers = new LinkedHashSet<>();
        for (ChangedSymbol c : seeds.values()) {
            changedHandlers.add(EndpointImpactCalculator.symbol(c.base().path(), c.base().qualifiedName()));
        }
        List<EndpointImpact> endpoints = endpointCalculator.calculate(endpointsOf(baseSnapshot), endpointsOf(headSnapshot),
                changedHandlers, affected);

        return new ImpactAnalysis(ALGORITHM_VERSION, depth, changed, List.copyOf(affected), possible, endpoints,
                mapping.unmappedHunks(), changes.nonJavaFileCount(), truncated, dispatchNotes(notFollowed));
    }

    /** One plain sentence per widely implemented method whose callers were not followed. */
    static List<String> dispatchNotes(Map<String, Integer> notFollowed) {
        List<String> out = new ArrayList<>();
        notFollowed.forEach((method, implementations) -> out.add("Callers of " + method + " were not followed: "
                + "the changed code implements it, but so do " + (implementations - 1) + " other class"
                + (implementations == 2 ? "" : "es") + ", so a call to it rarely runs the changed code. Check its callers "
                + "by hand if the change alters behaviour they rely on."));
        return List.copyOf(out);
    }

    /** The endpoints found by the index job a snapshot was built from; none if that job no longer exists. */
    private List<IndexedEndpoint> endpointsOf(UUID snapshot) {
        UUID job = snapshots.get(snapshot).sourceJobId();
        return job == null ? List.of() : symbols.allEndpoints(job);
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
        out.sort(BY_DISTANCE);
        return List.copyOf(out);
    }

    private static final Comparator<AffectedSymbol> BY_DISTANCE = Comparator.comparingInt(AffectedSymbol::distance)
            .thenComparing(AffectedSymbol::qualifiedName).thenComparing(AffectedSymbol::signature);

    /** Where a test link starts: a changed or affected production symbol and how it is linked to the change. */
    private record LinkSource(UUID nodeId, String nodeType, String changedSymbol, boolean certain, double confidence,
                              List<Hop> chain) {
    }

    /**
     * Adds the test classes named after the class of each changed or affected production symbol (TESTS edges), unless
     * one of their test methods is already affected. The chain shows the naming link and, for a member, its class.
     *
     * @return whether the result cap stopped the additions
     */
    private boolean addNamedTests(UUID snapshot, Map<UUID, ChangedSymbol> seeds, List<AffectedSymbol> affected) {
        List<LinkSource> sources = new ArrayList<>();
        for (ChangedSymbol c : seeds.values()) {
            SymbolRef b = c.base();
            if (!SourcePaths.isTestPath(b.path())) {
                sources.add(new LinkSource(b.nodeId(), b.nodeType(), b.qualifiedName(), true, 1.0, List.of()));
            }
        }
        for (AffectedSymbol a : affected) {
            if (!a.test()) sources.add(new LinkSource(a.nodeId(), a.nodeType(), a.changedSymbol(), a.certain(), a.confidence(), a.chain()));
        }
        if (sources.isEmpty()) return false;

        int cap = limits.maxAffected() * 10;
        Map<UUID, EdgeView> containerOf = new LinkedHashMap<>();
        List<UUID> members = sources.stream().filter(s -> !"TYPE".equals(s.nodeType())).map(LinkSource::nodeId).toList();
        for (EdgeView e : graph.containersOf(snapshot, members, cap)) containerOf.putIfAbsent(e.dstId(), e);
        Map<UUID, List<LinkSource>> byType = new LinkedHashMap<>();
        for (LinkSource s : sources) {
            UUID type = "TYPE".equals(s.nodeType()) ? s.nodeId()
                    : containerOf.containsKey(s.nodeId()) ? containerOf.get(s.nodeId()).srcId() : null;
            if (type != null) byType.computeIfAbsent(type, k -> new ArrayList<>()).add(s);
        }

        Set<UUID> known = new LinkedHashSet<>(seeds.keySet());
        affected.forEach(a -> known.add(a.nodeId()));
        Map<UUID, AffectedSymbol> added = new LinkedHashMap<>();
        List<EdgeView> links = graph.testLinksTo(snapshot, byType.keySet(), cap);
        Map<UUID, NodeView> tests = new LinkedHashMap<>();
        graph.nodes(snapshot, links.stream().map(EdgeView::srcId).distinct().toList()).forEach(n -> tests.put(n.id(), n));
        for (EdgeView link : links) {
            NodeView test = tests.get(link.srcId());
            if (test == null || known.contains(test.id()) || hasAffectedMember(test, affected)) continue;
            for (LinkSource s : byType.get(link.dstId())) {
                List<Hop> chain = new ArrayList<>();
                chain.add(hop(link));
                if (!"TYPE".equals(s.nodeType())) chain.add(hop(containerOf.get(s.nodeId())));
                chain.addAll(s.chain());
                AffectedSymbol candidate = new AffectedSymbol(test.id(), test.nodeType(), test.qualifiedName(),
                        test.signature(), test.path(), test.startLine(), SourcePaths.displayModule(test.path()), true,
                        chain.size(), s.certain() && "RESOLVED".equals(link.resolution()),
                        Math.min(s.confidence(), link.confidence()), s.changedSymbol(), List.copyOf(chain));
                AffectedSymbol current = added.get(test.id());
                if (current == null || candidate.distance() < current.distance()
                        || (candidate.distance() == current.distance() && candidate.confidence() > current.confidence())) {
                    added.put(test.id(), candidate);
                }
            }
        }
        for (AffectedSymbol a : added.values()) {
            if (affected.size() >= limits.maxAffected()) return true;
            affected.add(a);
        }
        return false;
    }

    private static boolean hasAffectedMember(NodeView type, List<AffectedSymbol> affected) {
        String prefix = type.qualifiedName() + "#";
        return affected.stream().anyMatch(a -> a.qualifiedName().startsWith(prefix));
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
