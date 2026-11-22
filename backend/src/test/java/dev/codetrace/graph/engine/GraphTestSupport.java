package dev.codetrace.graph.engine;

import dev.codetrace.graph.model.GraphModel.Edge;
import dev.codetrace.graph.model.GraphModel.EdgeType;
import dev.codetrace.graph.model.GraphModel.FileFact;
import dev.codetrace.graph.model.GraphModel.ImportFact;
import dev.codetrace.graph.model.GraphModel.Input;
import dev.codetrace.graph.model.GraphModel.Limits;
import dev.codetrace.graph.model.GraphModel.Node;
import dev.codetrace.graph.model.GraphModel.RefFact;
import dev.codetrace.graph.model.GraphModel.Result;
import dev.codetrace.graph.model.GraphModel.SymbolFact;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor.FileExtraction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Builds graphs from real extractor output so resolver tests exercise the same facts production sees. */
public final class GraphTestSupport {
    static final UUID SNAPSHOT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private GraphTestSupport() {
    }

    public static Input facts(Map<String, String> sources) {
        JavaSymbolExtractor extractor = new JavaSymbolExtractor();
        List<FileFact> files = new ArrayList<>();
        List<SymbolFact> symbols = new ArrayList<>();
        List<ImportFact> imports = new ArrayList<>();
        List<RefFact> refs = new ArrayList<>();
        for (var e : new TreeMap<>(sources).entrySet()) {
            String path = e.getKey();
            FileExtraction x = extractor.extract(e.getValue());
            files.add(new FileFact(path, x.packageName(), x.status().name()));
            x.symbols().forEach(s -> symbols.add(new SymbolFact(path, s.kind(), s.subtype(), s.simpleName(),
                    s.qualifiedName(), s.signature(), s.parentQualifiedName(), s.modifiers(), s.startLine(), s.endLine())));
            x.imports().forEach(i -> imports.add(new ImportFact(path, i.name(), i.isStatic(), i.wildcard(), i.line())));
            x.references().forEach(r -> refs.add(new RefFact(path, r.ownerQualifiedName(), r.kind(), r.name(),
                    r.argCount(), r.receiverKind(), r.receiverType(), r.argTypes(), r.line())));
        }
        return new Input(files, symbols, imports, refs);
    }

    static Result build(Map<String, String> sources) {
        return new GraphBuilder().build(SNAPSHOT, facts(sources), new Limits(100_000, 1_000_000));
    }

    static Node node(Result r, String qn) {
        return r.nodes().stream().filter(n -> n.qualifiedName().equals(qn)).findFirst()
                .orElseThrow(() -> new AssertionError("no node " + qn));
    }

    static List<Edge> from(Result r, String srcQn, EdgeType type) {
        UUID src = node(r, srcQn).id();
        return r.edges().stream().filter(e -> e.srcId().equals(src) && e.type() == type).toList();
    }

    /** Target description: the node's qualified name, or {@code ?name} for an unresolved edge. */
    static String dst(Result r, Edge e) {
        if (e.dstId() == null) return "?" + e.dstName();
        return r.nodes().stream().filter(n -> n.id().equals(e.dstId())).findFirst().orElseThrow().qualifiedName();
    }

    static List<String> dsts(Result r, String srcQn, EdgeType type) {
        return from(r, srcQn, type).stream().map(e -> dst(r, e)).toList();
    }

    static Edge only(Result r, String srcQn, EdgeType type) {
        List<Edge> es = from(r, srcQn, type);
        if (es.size() != 1) throw new AssertionError("expected 1 " + type + " edge from " + srcQn + " but got " + es);
        return es.get(0);
    }
}
