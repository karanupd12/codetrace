package dev.codetrace.graph.engine;

import dev.codetrace.graph.model.GraphModel.FileFact;
import dev.codetrace.graph.model.GraphModel.ImportFact;
import dev.codetrace.graph.model.GraphModel.Node;
import dev.codetrace.graph.model.GraphModel.NodeType;
import dev.codetrace.graph.model.GraphModel.SymbolFact;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lookup structures over the nodes of one snapshot. Built once, read-only afterwards (except supertype registration). */
final class SymbolIndex {
    /** Per-file name-resolution context: package and imports. */
    record FileContext(String path, String packageName, Map<String, String> singleImports, List<String> wildcards,
                       List<ImportFact> staticImports) {
    }

    final Map<UUID, Node> nodeById = new HashMap<>();
    final Map<String, List<Node>> typesByQn = new HashMap<>();
    final Map<String, Node> byPathQn = new HashMap<>();              // path|qn -> TYPE/METHOD/FIELD
    final Map<String, List<Node>> nestedByParent = new HashMap<>();  // path|parentQn -> nested TYPE nodes
    final Map<UUID, Node> parentType = new HashMap<>();              // member or nested type -> enclosing TYPE node
    final Map<UUID, List<Node>> methodsByType = new HashMap<>();
    final Map<UUID, List<Node>> fieldsByType = new HashMap<>();
    final Map<String, Node> fileNodes = new HashMap<>();             // path -> FILE node
    final Map<String, Node> packageNodes = new HashMap<>();          // package name -> PACKAGE node
    final Map<String, FileContext> files = new LinkedHashMap<>();
    /** Resolved direct supertypes (class first, then interfaces), registered while type edges are built. */
    final Map<UUID, List<Node>> supertypes = new HashMap<>();
    /** Types with at least one supertype that is not RESOLVED to a single repository type. */
    final java.util.Set<UUID> incompleteSupertypes = new java.util.HashSet<>();

    static String key(String path, String qn) {
        return path + "|" + qn;
    }

    void addNode(Node n) {
        nodeById.put(n.id(), n);
        switch (n.type()) {
            case FILE -> fileNodes.put(n.path(), n);
            case PACKAGE -> packageNodes.put(n.qualifiedName(), n);
            case TYPE -> {
                typesByQn.computeIfAbsent(n.qualifiedName(), k -> new ArrayList<>()).add(n);
                byPathQn.put(key(n.path(), n.qualifiedName()), n);
            }
            case METHOD, FIELD -> byPathQn.put(key(n.path(), n.qualifiedName()), n);
        }
    }

    /** Links members and nested types to their enclosing type. Must run after all nodes are added. */
    void linkParents(List<SymbolFact> symbols, Map<String, Node> nodeBySymbolKey) {
        for (SymbolFact s : symbols) {
            Node child = nodeBySymbolKey.get(symbolKey(s));
            if (child == null) continue;
            Node parent = byPathQn.get(key(s.path(), s.parentQualifiedName()));
            if (parent == null || parent.type() != NodeType.TYPE) continue; // top-level types have the package as "parent"
            parentType.put(child.id(), parent);
            switch (child.type()) {
                case TYPE -> nestedByParent.computeIfAbsent(key(s.path(), s.parentQualifiedName()), k -> new ArrayList<>())
                        .add(child);
                case METHOD -> methodsByType.computeIfAbsent(parent.id(), k -> new ArrayList<>()).add(child);
                case FIELD -> fieldsByType.computeIfAbsent(parent.id(), k -> new ArrayList<>()).add(child);
                default -> { }
            }
        }
    }

    static String symbolKey(SymbolFact s) {
        return s.path() + "|" + s.kind() + "|" + s.qualifiedName() + "|" + s.signature();
    }

    void addFileContext(FileFact f, List<ImportFact> imports) {
        Map<String, String> single = new HashMap<>();
        List<String> wildcards = new ArrayList<>();
        List<ImportFact> statics = new ArrayList<>();
        for (ImportFact i : imports) {
            if (i.isStatic()) {
                statics.add(i);
            } else if (i.wildcard()) {
                wildcards.add(i.name().substring(0, i.name().length() - 2)); // strip ".*"
            } else {
                single.putIfAbsent(i.name().substring(i.name().lastIndexOf('.') + 1), i.name());
            }
        }
        files.put(f.path(), new FileContext(f.path(), f.packageName(), single, wildcards, statics));
    }

    /** Enclosing types of a declaration, innermost first. For a TYPE node that is the type itself first. */
    List<Node> enclosingTypes(Node owner) {
        List<Node> chain = new ArrayList<>();
        Node t = owner.type() == NodeType.TYPE ? owner : parentType.get(owner.id());
        int guard = 0;
        while (t != null && guard++ < 64) {
            chain.add(t);
            t = parentType.get(t.id());
        }
        return chain;
    }

    List<Node> nestedTypes(Node type) {
        return nestedByParent.getOrDefault(key(type.path(), type.qualifiedName()), List.of());
    }

    List<Node> methodsOf(Node type) {
        return methodsByType.getOrDefault(type.id(), List.of());
    }

    List<Node> supertypesOf(Node type) {
        return supertypes.getOrDefault(type.id(), List.of());
    }
}

