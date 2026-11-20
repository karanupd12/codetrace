package dev.codetrace.graph;

import dev.codetrace.graph.GraphModel.ImportFact;
import dev.codetrace.graph.GraphModel.Node;
import dev.codetrace.graph.GraphModel.RefFact;
import dev.codetrace.graph.GraphModel.Resolution;
import dev.codetrace.graph.SymbolIndex.FileContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves call sites to methods or constructors by receiver, name and argument count. Argument <i>types</i> are not
 * checked, so a match by arity alone is reported with reduced confidence and equal-arity overloads stay AMBIGUOUS.
 * A hierarchy that reaches outside the repository is flagged instead of treated as proof of absence.
 */
final class CallResolver {
    /** Outcome for one call site. A null outcome means "no edge" (e.g. an implicit default constructor). */
    record Outcome(Resolution state, List<Node> targets, String evidenceKind, double confidence, String reason,
                   String dstName) {
    }

    private record Lookup(List<Node> exact, boolean nameExistsWithOtherArity, boolean incomplete, int namesAtLevel) {
    }

    private static final int MAX_HIERARCHY_DEPTH = 24;

    private final SymbolIndex index;
    private final TypeResolver types;

    CallResolver(SymbolIndex index, TypeResolver types) {
        this.index = index;
        this.types = types;
    }

    Outcome resolve(RefFact r, Node owner, FileContext ctx, List<Node> chain) {
        boolean ctor = r.name().equals("<init>");
        String shortName = r.name() + "/" + r.argCount();
        switch (r.receiverKind()) {
            case "TYPE": {
                TypeResolver.Resolved rt = types.resolve(r.receiverType(), ctx, chain);
                String label = r.receiverType() + "#" + shortName;
                if (rt.state() == Resolution.AMBIGUOUS) return unresolved("RECEIVER_TYPE_AMBIGUOUS", label, "RECEIVER");
                if (rt.state() == Resolution.UNRESOLVED) {
                    return unresolved("RECEIVER_TYPE_NOT_IN_REPOSITORY", label, "RECEIVER");
                }
                Node t = rt.single();
                String prefix = "RECEIVER_" + rt.evidenceKind() + "/";
                return ctor ? constructor(t, r.argCount(), null, prefix, label)
                        : fromLookup(lookup(t, r.name(), r.argCount(), false), prefix, t.qualifiedName() + "#" + shortName);
            }
            case "THIS": {
                if (chain.isEmpty()) return unresolved("RECEIVER_TYPE_UNKNOWN", "?#" + shortName, "THIS");
                Node t = chain.get(0);
                return ctor ? constructor(t, r.argCount(), owner, "THIS/", t.qualifiedName() + "#" + shortName)
                        : fromLookup(lookup(t, r.name(), r.argCount(), false), "THIS/", t.qualifiedName() + "#" + shortName);
            }
            case "SUPER": {
                if (chain.isEmpty()) return unresolved("RECEIVER_TYPE_UNKNOWN", "?#" + shortName, "SUPER");
                Node t = chain.get(0);
                if (ctor) return superConstructor(t, r.argCount(), t.qualifiedName() + "#super." + shortName);
                if (index.supertypesOf(t).isEmpty()) {
                    return unresolved("SUPERTYPE_NOT_IN_REPOSITORY", t.qualifiedName() + "#super." + shortName, "SUPER");
                }
                return fromLookup(lookup(t, r.name(), r.argCount(), true), "SUPER/", t.qualifiedName() + "#super." + shortName);
            }
            case "NONE":
                return unqualified(r, ctx, chain, shortName);
            default:
                return unresolved("RECEIVER_TYPE_UNKNOWN", "?#" + shortName, "UNKNOWN_RECEIVER");
        }
    }

    // ---- unqualified calls: enclosing scopes outward, then static imports --------------------------------------------

    private Outcome unqualified(RefFact r, FileContext ctx, List<Node> chain, String shortName) {
        boolean anyArityMismatch = false;
        boolean anyIncomplete = false;
        for (Node t : chain) {
            Lookup l = lookup(t, r.name(), r.argCount(), false);
            if (!l.exact().isEmpty()) return fromLookup(l, "ENCLOSING_SCOPE/", t.qualifiedName() + "#" + shortName);
            anyArityMismatch |= l.nameExistsWithOtherArity();
            anyIncomplete |= l.incomplete();
        }
        for (ImportFact si : ctx.staticImports()) {
            String typeFqn;
            if (si.wildcard()) {
                typeFqn = si.name().substring(0, si.name().length() - 2);
            } else if (si.name().endsWith("." + r.name())) {
                typeFqn = si.name().substring(0, si.name().length() - r.name().length() - 1);
            } else {
                continue;
            }
            TypeResolver.Resolved rt = types.resolveQualified(typeFqn, ctx);
            if (rt.state() == Resolution.RESOLVED) {
                Lookup l = lookup(rt.single(), r.name(), r.argCount(), false);
                if (!l.exact().isEmpty()) return fromLookup(l, "STATIC_IMPORT/", rt.single().qualifiedName() + "#" + shortName);
                anyArityMismatch |= l.nameExistsWithOtherArity();
            } else {
                anyIncomplete = true; // a static import from a type outside the repository may provide the method
            }
        }
        String dst = (chain.isEmpty() ? "?" : chain.get(0).qualifiedName()) + "#" + shortName;
        String reason = anyArityMismatch ? "ARITY_MISMATCH" : anyIncomplete ? "EXTERNAL_SUPERTYPE" : "METHOD_NOT_FOUND";
        return unresolved(reason, dst, "ENCLOSING_SCOPE");
    }

    // ---- method lookup through the supertype hierarchy -----------------------------------------------------------------

    /**
     * Finds methods named {@code name} accepting {@code argc} arguments, nearest type first. The first hierarchy level
     * with any match wins (an override hides what it overrides).
     */
    private Lookup lookup(Node start, String name, int argc, boolean skipStart) {
        Set<java.util.UUID> seen = new HashSet<>();
        List<Node> level = new ArrayList<>(skipStart ? index.supertypesOf(start) : List.of(start));
        boolean incomplete = skipStart && index.incompleteSupertypes.contains(start.id());
        boolean otherArity = false;
        for (int depth = 0; !level.isEmpty() && depth < MAX_HIERARCHY_DEPTH; depth++) {
            List<Node> exact = new ArrayList<>();
            int names = 0;
            List<Node> next = new ArrayList<>();
            for (Node t : level) {
                if (!seen.add(t.id())) continue;
                for (Node m : index.methodsOf(t)) {
                    if (!methodName(m).equals(name)) continue;
                    names++;
                    if (arityAccepts(m, argc)) exact.add(m); else otherArity = true;
                }
                incomplete |= index.incompleteSupertypes.contains(t.id());
                next.addAll(index.supertypesOf(t));
            }
            if (!exact.isEmpty()) return new Lookup(exact, otherArity, incomplete, names);
            level = next;
        }
        return new Lookup(List.of(), otherArity, incomplete, 0);
    }

    private Outcome fromLookup(Lookup l, String evidencePrefix, String dstName) {
        if (l.exact().size() == 1) {
            boolean uniqueName = l.namesAtLevel() == 1;
            return new Outcome(Resolution.RESOLVED, l.exact(), evidencePrefix + (uniqueName ? "UNIQUE_NAME" : "UNIQUE_NAME_AND_ARITY"),
                    uniqueName ? 1.0 : 0.9, "", dstName);
        }
        if (l.exact().size() > 1) {
            return new Outcome(Resolution.AMBIGUOUS, l.exact(), evidencePrefix + "SAME_ARITY_OVERLOADS",
                    0.9 / l.exact().size(), "MULTIPLE_CANDIDATES", dstName);
        }
        String reason = l.nameExistsWithOtherArity() ? "ARITY_MISMATCH" : l.incomplete() ? "EXTERNAL_SUPERTYPE" : "METHOD_NOT_FOUND";
        return unresolved(reason, dstName, evidencePrefix + "NOT_FOUND");
    }

    // ---- constructors ----------------------------------------------------------------------------------------------------

    private Outcome constructor(Node type, int argc, Node excludeSelf, String prefix, String dstName) {
        List<Node> ctors = new ArrayList<>();
        for (Node m : index.methodsOf(type)) {
            if (methodName(m).equals("<init>") && (excludeSelf == null || !m.id().equals(excludeSelf.id()))) ctors.add(m);
        }
        List<Node> exact = ctors.stream().filter(c -> arityAccepts(c, argc)).toList();
        if (exact.isEmpty()) {
            if (implicitConstructorCovers(type, argc, ctors)) return null; // implicit default / canonical record ctor
            return unresolved(ctors.isEmpty() ? "METHOD_NOT_FOUND" : "ARITY_MISMATCH", dstName, prefix + "NOT_FOUND");
        }
        return fromLookup(new Lookup(exact, false, false, ctors.size()), prefix, dstName);
    }

    private Outcome superConstructor(Node type, int argc, String dstName) {
        for (Node sup : index.supertypesOf(type)) {
            if (!"INTERFACE".equals(sup.subtype())) return constructor(sup, argc, null, "SUPER/", dstName);
        }
        if (index.incompleteSupertypes.contains(type.id())) {
            return unresolved("SUPERTYPE_NOT_IN_REPOSITORY", dstName, "SUPER");
        }
        return argc == 0 ? null : unresolved("SUPERTYPE_NOT_IN_REPOSITORY", dstName, "SUPER"); // implicit Object()
    }

    private boolean implicitConstructorCovers(Node type, int argc, List<Node> declared) {
        if (declared.isEmpty() && argc == 0) return true;
        if ("RECORD".equals(type.subtype())) {
            long components = index.fieldsByType.getOrDefault(type.id(), List.of()).stream()
                    .filter(f -> "RECORD_COMPONENT".equals(f.subtype())).count();
            return argc == components;
        }
        return false;
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private static Outcome unresolved(String reason, String dstName, String evidenceKind) {
        return new Outcome(Resolution.UNRESOLVED, List.of(), evidenceKind, 0.0, reason, dstName);
    }

    static String methodName(Node m) {
        String sig = m.signature();
        int p = sig.indexOf('(');
        return p < 0 ? sig : sig.substring(0, p);
    }

    /** Parameter list of a signature like {@code put(Map<String,List<Integer>>,int...)}, split at top-level commas. */
    static List<String> parameters(String signature) {
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close <= open + 1) return List.of();
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int i = open + 1; i < close; i++) {
            char c = signature.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                out.add(signature.substring(start, i));
                start = i + 1;
            }
        }
        out.add(signature.substring(start, close));
        return out;
    }

    static boolean arityAccepts(Node m, int argc) {
        List<String> params = parameters(m.signature());
        if (params.size() == argc) return true;
        return !params.isEmpty() && params.get(params.size() - 1).endsWith("...") && argc >= params.size() - 1;
    }
}
