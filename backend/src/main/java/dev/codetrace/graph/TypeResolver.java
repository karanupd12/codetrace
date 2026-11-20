package dev.codetrace.graph;

import dev.codetrace.graph.GraphModel.Node;
import dev.codetrace.graph.GraphModel.Resolution;
import dev.codetrace.graph.SymbolIndex.FileContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves a type name as written in source to repository types, following Java's scoping order:
 * enclosing scopes, single-type imports, the same package, on-demand (wildcard) imports, then fully qualified names.
 * Anything it cannot prove stays UNRESOLVED or AMBIGUOUS, and says why.
 */
final class TypeResolver {
    /** Outcome of resolving one name. {@code targets} is empty for UNRESOLVED. */
    record Resolved(Resolution state, List<Node> targets, String evidenceKind, double confidence, String reason,
                    String dstName) {
        Node single() {
            return state == Resolution.RESOLVED ? targets.get(0) : null;
        }
    }

    private final SymbolIndex index;
    private final Map<String, Resolved> cache = new HashMap<>();

    TypeResolver(SymbolIndex index) {
        this.index = index;
    }

    /** @param chain enclosing types of the referencing declaration, innermost first */
    Resolved resolve(String name, FileContext ctx, List<Node> chain) {
        String cacheKey = ctx.path() + "|" + (chain.isEmpty() ? "" : chain.get(0).id()) + "|" + name;
        Resolved hit = cache.get(cacheKey);
        if (hit != null) return hit;
        Resolved r = compute(name, ctx, chain);
        cache.put(cacheKey, r);
        return r;
    }

    /** Resolves a fully qualified name (imports, static imports); module preference settles duplicate declarations. */
    Resolved resolveQualified(String fqn, FileContext ctx) {
        List<Node> found = index.typesByQn.getOrDefault(fqn, List.of());
        if (found.isEmpty()) return unresolved("NOT_DECLARED_IN_REPOSITORY", fqn, "NO_MATCH");
        return disambiguate(found, "FULLY_QUALIFIED", 1.0, ctx);
    }

    private Resolved compute(String name, FileContext ctx, List<Node> chain) {
        String[] seg = name.split("\\.");
        String first = seg[0];

        // 1. enclosing scopes: the type itself, or a member type of it or of an outer type
        for (Node t : chain) {
            if (t.simpleName().equals(first)) {
                return descend(List.of(t), seg, "ENCLOSING_TYPE", 1.0, ctx, name);
            }
            List<Node> nested = index.nestedTypes(t).stream().filter(n -> n.simpleName().equals(first)).toList();
            if (!nested.isEmpty()) {
                return descend(nested, seg, "ENCLOSING_TYPE", 1.0, ctx, name);
            }
        }
        // 2. single-type import (shadows the same package)
        String imported = ctx.singleImports().get(first);
        if (imported != null) {
            List<Node> found = index.typesByQn.getOrDefault(imported, List.of());
            if (found.isEmpty()) {
                return unresolved("NOT_DECLARED_IN_REPOSITORY", rest(imported, seg), "IMPORT_EXTERNAL");
            }
            return descend(found, seg, "IMPORT", 1.0, ctx, name);
        }
        // 3. same package
        String samePackage = ctx.packageName().isEmpty() ? first : ctx.packageName() + "." + first;
        List<Node> inPackage = index.typesByQn.getOrDefault(samePackage, List.of());
        if (!inPackage.isEmpty()) {
            return descend(inPackage, seg, "SAME_PACKAGE", 1.0, ctx, name);
        }
        // 4. on-demand imports: cannot exclude a colliding external type, so confidence is below 1
        Map<UUID, Node> wild = new LinkedHashMap<>();
        for (String w : ctx.wildcards()) {
            for (Node n : index.typesByQn.getOrDefault(w + "." + first, List.of())) wild.put(n.id(), n);
        }
        if (!wild.isEmpty()) {
            long distinctNames = wild.values().stream().map(Node::qualifiedName).distinct().count();
            if (distinctNames > 1) { // different types with one simple name: a genuine ambiguity, not a module choice
                List<Node> all = new ArrayList<>(wild.values());
                return new Resolved(Resolution.AMBIGUOUS, all, "WILDCARD_IMPORT", 0.9 / all.size(),
                        "MULTIPLE_DECLARATIONS", all.get(0).qualifiedName());
            }
            return descend(new ArrayList<>(wild.values()), seg, "WILDCARD_IMPORT", 0.9, ctx, name);
        }
        // 5. fully qualified name
        List<Node> qualified = index.typesByQn.getOrDefault(name, List.of());
        if (seg.length > 1 && !qualified.isEmpty()) {
            return disambiguate(qualified, "FULLY_QUALIFIED", 1.0, ctx);
        }
        return unresolved("NOT_DECLARED_IN_REPOSITORY", name, "NO_MATCH");
    }

    /** Walks the remaining dotted segments through member types, then settles duplicates. */
    private Resolved descend(List<Node> start, String[] seg, String kind, double conf, FileContext ctx, String name) {
        List<Node> current = start;
        for (int i = 1; i < seg.length; i++) {
            String s = seg[i];
            List<Node> next = new ArrayList<>();
            for (Node t : current) {
                index.nestedTypes(t).stream().filter(n -> n.simpleName().equals(s)).forEach(next::add);
            }
            if (next.isEmpty()) return unresolved("NESTED_TYPE_NOT_FOUND", name, kind);
            current = next;
        }
        return disambiguate(current, kind, conf, ctx);
    }

    /**
     * Several declarations may share one qualified name (e.g. two Maven modules with the same package). Prefer the
     * declaration in the referencing file's own module; otherwise report every candidate as AMBIGUOUS.
     */
    private Resolved disambiguate(List<Node> candidates, String kind, double conf, FileContext ctx) {
        Map<UUID, Node> distinct = new LinkedHashMap<>();
        candidates.forEach(c -> distinct.putIfAbsent(c.id(), c));
        List<Node> all = new ArrayList<>(distinct.values());
        if (all.size() == 1) {
            return new Resolved(Resolution.RESOLVED, all, kind, conf, "", all.get(0).qualifiedName());
        }
        String module = SymbolIndex.moduleOf(ctx.path());
        List<Node> sameModule = all.stream().filter(n -> SymbolIndex.moduleOf(n.path()).equals(module)).toList();
        if (sameModule.size() == 1) {
            return new Resolved(Resolution.RESOLVED, sameModule, kind + "+SAME_MODULE", conf * 0.9, "",
                    sameModule.get(0).qualifiedName());
        }
        List<Node> pool = sameModule.isEmpty() ? all : sameModule;
        return new Resolved(Resolution.AMBIGUOUS, pool, kind, conf / pool.size(), "MULTIPLE_DECLARATIONS",
                pool.get(0).qualifiedName());
    }

    private static Resolved unresolved(String reason, String dstName, String kind) {
        return new Resolved(Resolution.UNRESOLVED, List.of(), kind, 0.0, reason, dstName);
    }

    private static String rest(String imported, String[] seg) {
        if (seg.length == 1) return imported;
        return imported + "." + String.join(".", java.util.Arrays.copyOfRange(seg, 1, seg.length));
    }
}
