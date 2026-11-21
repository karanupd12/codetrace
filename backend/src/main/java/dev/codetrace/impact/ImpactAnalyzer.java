package dev.codetrace.impact;

import dev.codetrace.change.ChangeSet;
import dev.codetrace.change.ChangeSet.FileChange;
import dev.codetrace.change.ChangedSymbolMapper;
import dev.codetrace.change.ChangedSymbolMapper.ChangedSymbol;
import dev.codetrace.change.ChangedSymbolMapper.Kind;
import dev.codetrace.change.ChangedSymbolMapper.Mapping;
import dev.codetrace.change.SnapshotDeclarationDao;
import dev.codetrace.change.SymbolRef;
import dev.codetrace.config.ImpactProperties;
import dev.codetrace.graph.GraphQueryDao;
import dev.codetrace.graph.GraphQueryDao.Direction;
import dev.codetrace.graph.GraphQueryDao.EdgeView;
import dev.codetrace.graph.GraphQueryDao.NodeView;
import dev.codetrace.impact.BlastRadiusCalculator.Affected;
import dev.codetrace.impact.BlastRadiusCalculator.Result;
import dev.codetrace.impact.ImpactAnalysis.AffectedSymbol;
import dev.codetrace.impact.ImpactAnalysis.ChangedEntry;
import dev.codetrace.impact.ImpactAnalysis.Hop;
import dev.codetrace.impact.ImpactAnalysis.PossibleDependent;
import dev.codetrace.impact.ImpactDao.UnresolvedCaller;
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
    public static final String ALGORITHM_VERSION = "impact-1";
    /** Edge types that make one symbol depend on another. Containment and imports are structure, not dependency. */
    static final Set<String> DEPENDENCY_EDGES = Set.of("CALLS", "REFERENCES", "EXTENDS", "IMPLEMENTS");
    private static final Set<String> TRAVERSABLE = Set.of("RESOLVED", "AMBIGUOUS");
    private static final int MAX_POSSIBLE = 200;

    private final ChangedSymbolMapper mapper;
    private final SnapshotDeclarationDao declarations;
    private final GraphQueryDao graph;
    private final ImpactDao impactDao;
    private final ImpactProperties limits;
    private final BlastRadiusCalculator calculator = new BlastRadiusCalculator();

    public ImpactAnalyzer(ChangedSymbolMapper mapper, SnapshotDeclarationDao declarations, GraphQueryDao graph,
                          ImpactDao impactDao, ImpactProperties limits) {
        this.mapper = mapper;
        this.declarations = declarations;
        this.graph = graph;
        this.impactDao = impactDao;
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
        Mapping mapping = mapper.map(changes, declarations.byPath(baseSnapshot, oldPaths), declarations.byPath(headSnapshot, newPaths));

        // seeds: symbols that existed before the change and were modified or removed
        Map<UUID, ChangedSymbol> seeds = new LinkedHashMap<>();
        for (ChangedSymbol c : mapping.symbols()) {
            if (c.base() != null && c.kind() != Kind.ADDED) seeds.put(c.base().nodeId(), c);
        }

        int edgeCap = limits.maxAffected() * 10;
        Result radius = calculator.calculate(new LinkedHashSet<>(seeds.keySet()), depth, limits.maxAffected(),
                frontier -> graph.touching(baseSnapshot, frontier, Direction.IN, DEPENDENCY_EDGES, TRAVERSABLE, true, edgeCap),
                edgeCap);

        Map<UUID, Integer> direct = impactDao.directDependents(baseSnapshot, seeds.keySet(), DEPENDENCY_EDGES);
        List<ChangedEntry> changed = mapping.symbols().stream()
                .map(c -> new ChangedEntry(c.kind(), c.base(), c.head(), c.base() == null ? 0 : direct.getOrDefault(c.base().nodeId(), 0)))
                .toList();

        List<AffectedSymbol> affected = describe(baseSnapshot, radius.affected(), seeds);
        List<PossibleDependent> possible = possibleDependents(baseSnapshot, seeds, affected);

        return new ImpactAnalysis(ALGORITHM_VERSION, depth, changed, affected, possible, mapping.unmappedHunks(),
                changes.nonJavaFileCount(), radius.truncated());
    }

    private int clampDepth(Integer requested) {
        int d = requested == null ? limits.defaultDepth() : requested;
        return Math.max(1, Math.min(d, limits.maxDepth()));
    }

    private List<AffectedSymbol> describe(UUID snapshot, List<Affected> found, Map<UUID, ChangedSymbol> seeds) {
        Map<UUID, NodeView> nodes = new LinkedHashMap<>();
        graph.nodesByIds(snapshot, found.stream().map(Affected::nodeId).toList()).forEach(n -> nodes.put(n.id(), n));
        List<AffectedSymbol> out = new ArrayList<>();
        for (Affected a : found) {
            NodeView n = nodes.get(a.nodeId());
            if (n == null) continue; // cannot happen under the snapshot's foreign keys; never invent a symbol
            ChangedSymbol seed = seeds.get(a.seedId());
            List<Hop> chain = a.path().stream().map(ImpactAnalyzer::hop).toList();
            out.add(new AffectedSymbol(n.id(), n.nodeType(), n.qualifiedName(), n.signature(), n.path(), n.startLine(),
                    ImpactAnalysis.moduleOf(n.path()), ImpactAnalysis.isTestPath(n.path()), a.distance(), a.certain(),
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
            int[] arity = arityRange(b.signature());
            changedByName.computeIfAbsent(name, k -> new ArrayList<>()).add(new ChangedMethod(b.qualifiedName(), arity[0], arity[1]));
        }
        Set<UUID> known = new LinkedHashSet<>(seeds.keySet());
        affected.forEach(a -> known.add(a.nodeId()));
        List<PossibleDependent> out = new ArrayList<>();
        for (UnresolvedCaller u : impactDao.unknownReceiverCallers(snapshot, changedByName.keySet(), MAX_POSSIBLE)) {
            if (known.contains(u.nodeId())) continue;
            changedByName.getOrDefault(u.methodName(), List.of()).stream().filter(m -> m.accepts(u.argCount())).findFirst()
                    .ifPresent(m -> out.add(new PossibleDependent(u.nodeId(), u.qualifiedName(), u.path(), u.line(), m.qualifiedName())));
        }
        return List.copyOf(out);
    }

    /**
     * Minimum and maximum number of arguments a method with this signature accepts: {@code "(int,Map<K,V>)"} is 2 to
     * 2, {@code "(String,int...)"} is 1 and up. Commas inside generic type arguments do not separate parameters.
     */
    static int[] arityRange(String signature) {
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        String inner = open < 0 || close < open ? "" : signature.substring(open + 1, close).trim();
        if (inner.isEmpty()) return new int[] {0, 0};
        int params = 1;
        int depth = 0;
        for (char ch : inner.toCharArray()) {
            if (ch == '<' || ch == '(') depth++;
            else if (ch == '>' || ch == ')') depth--;
            else if (ch == ',' && depth == 0) params++;
        }
        return inner.endsWith("...") ? new int[] {params - 1, Integer.MAX_VALUE} : new int[] {params, params};
    }
}
