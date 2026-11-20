package dev.codetrace.graph;

import dev.codetrace.graph.GraphModel.Edge;
import dev.codetrace.graph.GraphModel.EdgeType;
import dev.codetrace.graph.GraphModel.FileFact;
import dev.codetrace.graph.GraphModel.ImportFact;
import dev.codetrace.graph.GraphModel.Input;
import dev.codetrace.graph.GraphModel.Limits;
import dev.codetrace.graph.GraphModel.Node;
import dev.codetrace.graph.GraphModel.NodeType;
import dev.codetrace.graph.GraphModel.RefFact;
import dev.codetrace.graph.GraphModel.Resolution;
import dev.codetrace.graph.GraphModel.Result;
import dev.codetrace.graph.GraphModel.SymbolFact;
import dev.codetrace.graph.SymbolIndex.FileContext;
import dev.codetrace.ingestion.IngestionException;
import dev.codetrace.ingestion.IngestionException.Category;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Builds a dependency graph from extracted facts. Pure and deterministic: the same input and snapshot id always give
 * the same nodes, edges and ids. Every edge carries provenance and an explicit resolution state; uncertainty is
 * recorded, never guessed away. Bounded by {@link Limits}.
 */
public final class GraphBuilder {
    /** Recorded on every snapshot and edge so results are traceable to the algorithm that produced them. */
    public static final String VERSION = "graph-builder-1";
    private static final Set<String> INCLUDED_STATUSES = Set.of("PARSED", "PARTIAL");

    public Result build(UUID snapshotId, Input in, Limits limits) {
        SymbolIndex idx = new SymbolIndex();
        Map<String, Node> nodeBySymbolKey = new HashMap<>();

        // ---- nodes ----
        List<FileFact> files = in.files().stream().filter(f -> INCLUDED_STATUSES.contains(f.status()))
                .sorted(Comparator.comparing(FileFact::path)).toList();
        Set<String> paths = new HashSet<>();
        for (FileFact f : files) {
            paths.add(f.path());
            String simple = f.path().substring(f.path().lastIndexOf('/') + 1);
            idx.addNode(node(snapshotId, NodeType.FILE, f.path(), "", simple, "", f.path(), 0, 0, ""));
        }
        files.stream().map(FileFact::packageName).filter(p -> !p.isEmpty()).distinct().sorted().forEach(p ->
                idx.addNode(node(snapshotId, NodeType.PACKAGE, p, "", p.substring(p.lastIndexOf('.') + 1), "", "", 0, 0, "")));

        List<SymbolFact> symbols = in.symbols().stream().filter(s -> paths.contains(s.path()))
                .sorted(Comparator.comparing(SymbolFact::path).thenComparingInt(SymbolFact::startLine)
                        .thenComparing(SymbolFact::qualifiedName).thenComparing(SymbolFact::signature)).toList();
        List<SymbolFact> kept = new ArrayList<>();
        for (SymbolFact s : symbols) {
            String k = SymbolIndex.symbolKey(s);
            if (nodeBySymbolKey.containsKey(k)) continue; // duplicate identity: first declaration wins
            Node n = node(snapshotId, NodeType.valueOf(s.kind()), s.qualifiedName(), s.signature(), s.simpleName(),
                    s.subtype(), s.path(), s.startLine(), s.endLine(), s.modifiers());
            nodeBySymbolKey.put(k, n);
            idx.addNode(n);
            kept.add(s);
        }
        if (idx.nodeById.size() > limits.maxNodes()) {
            throw new IngestionException(Category.LIMIT_EXCEEDED, "Graph would exceed " + limits.maxNodes() + " nodes.");
        }
        idx.linkParents(kept, nodeBySymbolKey);

        Map<String, List<ImportFact>> importsByPath = new HashMap<>();
        for (ImportFact i : in.imports()) {
            if (paths.contains(i.path())) importsByPath.computeIfAbsent(i.path(), k -> new ArrayList<>()).add(i);
        }
        for (FileFact f : files) idx.addFileContext(f, importsByPath.getOrDefault(f.path(), List.of()));

        TypeResolver types = new TypeResolver(idx);
        CallResolver calls = new CallResolver(idx, types);
        EdgeSink sink = new EdgeSink(limits.maxEdges());
        Map<String, Integer> stats = new TreeMap<>();

        contain(idx, files, sink);
        importEdges(idx, types, in, paths, sink);

        List<RefFact> refs = in.refs().stream().filter(r -> paths.contains(r.path()))
                .sorted(Comparator.comparing(RefFact::path).thenComparingInt(RefFact::line)
                        .thenComparing(RefFact::ownerQualifiedName).thenComparing(RefFact::kind)
                        .thenComparing(RefFact::name)).toList();

        // Type hierarchy first: call resolution walks resolved supertypes.
        for (String kind : List.of("EXTENDS", "IMPLEMENTS")) {
            for (RefFact r : refs) {
                if (r.kind().equals(kind)) typeRef(idx, types, r, EdgeType.valueOf(kind), true, sink, stats);
            }
        }
        for (RefFact r : refs) {
            if (r.kind().equals("REFERENCES")) typeRef(idx, types, r, EdgeType.REFERENCES, false, sink, stats);
        }
        for (RefFact r : refs) {
            if (!r.kind().equals("CALLS")) continue;
            Node owner = idx.byPathQn.get(SymbolIndex.key(r.path(), r.ownerQualifiedName()));
            if (owner == null) {
                stats.merge("orphanReferences", 1, Integer::sum);
                continue;
            }
            CallResolver.Outcome o = calls.resolve(r, owner, idx.files.get(r.path()), idx.enclosingTypes(owner));
            if (o != null) {
                emit(sink, owner, EdgeType.CALLS, o.state(), o.targets(), o.evidenceKind(), o.confidence(), o.reason(),
                        o.dstName(), r.path(), r.line());
            }
        }

        List<Node> nodes = idx.nodeById.values().stream()
                .sorted(Comparator.comparing(Node::type).thenComparing(Node::path).thenComparing(Node::qualifiedName)
                        .thenComparing(Node::signature)).toList();
        List<Edge> edges = sink.sorted();
        for (Node n : nodes) stats.merge("nodes." + n.type(), 1, Integer::sum);
        for (Edge e : edges) {
            stats.merge("edges." + e.type(), 1, Integer::sum);
            stats.merge("edges." + e.type() + "." + e.resolution(), 1, Integer::sum);
        }
        return new Result(nodes, edges, stats);
    }

    // ---- structure ------------------------------------------------------------------------------------------------

    private void contain(SymbolIndex idx, List<FileFact> files, EdgeSink sink) {
        for (FileFact f : files) {
            Node file = idx.fileNodes.get(f.path());
            Node pkg = idx.packageNodes.get(f.packageName());
            if (pkg != null) sink.add(pkg, EdgeType.CONTAINS, file, "", Resolution.RESOLVED, 1.0, "DECLARATION", "", "",
                    f.path(), 0);
        }
        for (Node n : idx.nodeById.values()) {
            if (n.type() != NodeType.TYPE && n.type() != NodeType.METHOD && n.type() != NodeType.FIELD) continue;
            Node parent = idx.parentType.get(n.id());
            if (parent == null) parent = idx.fileNodes.get(n.path()); // top-level types belong to their file
            if (parent != null) sink.add(parent, EdgeType.CONTAINS, n, "", Resolution.RESOLVED, 1.0, "DECLARATION", "",
                    "", n.path(), n.startLine());
        }
    }

    private void importEdges(SymbolIndex idx, TypeResolver types, Input in, Set<String> paths, EdgeSink sink) {
        for (ImportFact i : in.imports().stream().filter(x -> paths.contains(x.path()))
                .sorted(Comparator.comparing(ImportFact::path).thenComparingInt(ImportFact::line)).toList()) {
            Node file = idx.fileNodes.get(i.path());
            FileContext ctx = idx.files.get(i.path());
            String name = i.name();
            if (i.wildcard()) {
                String base = name.substring(0, name.length() - 2);
                String kind = i.isStatic() ? "STATIC_IMPORT" : "WILDCARD_IMPORT";
                Node pkg = i.isStatic() ? null : idx.packageNodes.get(base);
                if (pkg != null) {
                    sink.add(file, EdgeType.IMPORTS, pkg, "", Resolution.RESOLVED, 1.0, kind, "", "", i.path(), i.line());
                    continue;
                }
                emitResolved(sink, file, EdgeType.IMPORTS, types.resolveQualified(base, ctx), name, kind, i.path(), i.line());
            } else {
                String typeName = i.isStatic() ? name.substring(0, Math.max(0, name.lastIndexOf('.'))) : name;
                emitResolved(sink, file, EdgeType.IMPORTS, types.resolveQualified(typeName, ctx), name,
                        i.isStatic() ? "STATIC_IMPORT" : "IMPORT", i.path(), i.line());
            }
        }
    }

    private void typeRef(SymbolIndex idx, TypeResolver types, RefFact r, EdgeType edgeType, boolean hierarchy,
                         EdgeSink sink, Map<String, Integer> stats) {
        Node owner = idx.byPathQn.get(SymbolIndex.key(r.path(), r.ownerQualifiedName()));
        if (owner == null) {
            stats.merge("orphanReferences", 1, Integer::sum);
            return;
        }
        TypeResolver.Resolved res = types.resolve(r.name(), idx.files.get(r.path()), idx.enclosingTypes(owner));
        if (hierarchy) {
            if (res.state() == Resolution.RESOLVED) {
                idx.supertypes.computeIfAbsent(owner.id(), k -> new ArrayList<>()).add(res.single());
            } else {
                idx.incompleteSupertypes.add(owner.id());
            }
        }
        if (res.state() == Resolution.RESOLVED && res.single().id().equals(owner.id())) return; // no self-references
        emitResolved(sink, owner, edgeType, res, r.name(), res.evidenceKind(), r.path(), r.line());
    }

    // ---- emission -------------------------------------------------------------------------------------------------

    private void emitResolved(EdgeSink sink, Node src, EdgeType type, TypeResolver.Resolved res, String writtenName,
                              String evidenceKind, String path, int line) {
        // Unresolved edges record what the resolver was looking for; ambiguous ones keep the name as written.
        emit(sink, src, type, res.state(), res.targets(), evidenceKind, res.confidence(), res.reason(),
                res.state() == Resolution.UNRESOLVED ? res.dstName() : writtenName, path, line);
    }

    private void emit(EdgeSink sink, Node src, EdgeType type, Resolution state, List<Node> targets, String evidenceKind,
                      double confidence, String reason, String dstName, String path, int line) {
        switch (state) {
            case RESOLVED -> sink.add(src, type, targets.get(0), "", Resolution.RESOLVED, confidence, evidenceKind, "", "",
                    path, line);
            case AMBIGUOUS -> {
                String group = UUID.nameUUIDFromBytes((src.id() + "|" + type + "|" + dstName + "|" + path + "|" + line)
                        .getBytes(StandardCharsets.UTF_8)).toString();
                for (Node t : targets) {
                    sink.add(src, type, t, dstName, Resolution.AMBIGUOUS, confidence, evidenceKind, reason, group, path, line);
                }
            }
            case UNRESOLVED -> sink.add(src, type, null, dstName, Resolution.UNRESOLVED, 0.0, evidenceKind, reason, "", path,
                    line);
        }
    }

    private static Node node(UUID snapshotId, NodeType type, String qn, String sig, String simple, String subtype,
                             String path, int start, int end, String modifiers) {
        UUID id = UUID.nameUUIDFromBytes((snapshotId + "|" + type + "|" + path + "|" + qn + "|" + sig)
                .getBytes(StandardCharsets.UTF_8));
        return new Node(id, type, qn, sig, simple, subtype, path, start, end, modifiers);
    }

    /** Collects edges, merging duplicates (same source, type, target) into one edge with an occurrence count. */
    private static final class EdgeSink {
        private final Map<String, Edge> edges = new LinkedHashMap<>();
        private final int max;

        EdgeSink(int max) {
            this.max = max;
        }

        void add(Node src, EdgeType type, Node dst, String dstName, Resolution res, double conf, String evidenceKind,
                 String reason, String group, String path, int line) {
            String key = src.id() + "|" + type + "|" + (dst == null ? "" : dst.id()) + "|" + (dst == null ? dstName : "")
                    + "|" + res;
            Edge existing = edges.get(key);
            if (existing != null) {
                edges.put(key, new Edge(existing.srcId(), existing.type(), existing.dstId(), existing.dstName(),
                        existing.resolution(), existing.confidence(), existing.evidenceKind(), existing.reason(),
                        existing.alternativeGroup(), existing.evidencePath(), existing.evidenceLine(),
                        existing.occurrences() + 1));
                return;
            }
            if (edges.size() >= max) {
                throw new IngestionException(Category.LIMIT_EXCEEDED, "Graph would exceed " + max + " edges.");
            }
            edges.put(key, new Edge(src.id(), type, dst == null ? null : dst.id(), dstName, res, conf, evidenceKind,
                    reason, group, path, line, 1));
        }

        List<Edge> sorted() {
            return edges.values().stream().sorted(Comparator.comparing((Edge e) -> e.srcId().toString())
                    .thenComparing(Edge::type).thenComparing(Edge::resolution)
                    .thenComparing(e -> e.dstId() == null ? "" : e.dstId().toString())
                    .thenComparing(e -> e.dstName() == null ? "" : e.dstName())).toList();
        }
    }
}
