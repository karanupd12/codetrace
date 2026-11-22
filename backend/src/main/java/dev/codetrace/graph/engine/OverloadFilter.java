package dev.codetrace.graph.engine;

import dev.codetrace.common.util.JavaSignatures;
import dev.codetrace.graph.engine.SymbolIndex.FileContext;
import dev.codetrace.graph.model.GraphModel.Node;
import dev.codetrace.graph.model.GraphModel.Resolution;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Narrows same-arity overload candidates using the argument type hints of a call site, following Java's invocation
 * rules: phase 1 (identity, widening), phase 2 (adds boxing and unboxing) and phase 3 (variable arity), then the most
 * specific candidate. A check is YES, NO or UNKNOWN; UNKNOWN (an argument without a hint, a type from outside the
 * repository that may be a type variable, a hierarchy that leaves the repository) never excludes a candidate. When
 * nothing can be decided the candidates are returned unchanged, so the arity-based result remains the fallback.
 */
final class OverloadFilter {
    private enum Fit { YES, NO, UNKNOWN }

    private enum Kind { PRIMITIVE, ARRAY, REPO, JDK, EXTERNAL, UNKNOWN, NULL }

    /** A type as far as the text and the repository reveal it. {@code element} is set for arrays. */
    private record Ty(Kind kind, String name, Node node, Ty element) {
        static final Ty UNKNOWN = new Ty(Kind.UNKNOWN, "", null, null);
        static final Ty NULL = new Ty(Kind.NULL, "null", null, null);
    }

    /** Declared parameter types of a candidate; for variable arity the last type is the array type. */
    private record Params(List<Ty> types, boolean varargs) {
    }

    private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double");
    private static final Map<String, Set<String>> WIDENING = Map.of(
            "byte", Set.of("short", "int", "long", "float", "double"),
            "short", Set.of("int", "long", "float", "double"),
            "char", Set.of("int", "long", "float", "double"),
            "int", Set.of("long", "float", "double"),
            "long", Set.of("float", "double"),
            "float", Set.of("double"));
    private static final Map<String, String> BOX = Map.of("boolean", "Boolean", "byte", "Byte", "short", "Short",
            "char", "Character", "int", "Integer", "long", "Long", "float", "Float", "double", "Double");
    private static final Map<String, String> UNBOX = Map.of("Boolean", "boolean", "Byte", "byte", "Short", "short",
            "Character", "char", "Integer", "int", "Long", "long", "Float", "float", "Double", "double");
    /** Supertypes of the JDK types we know by name (besides Object). */
    private static final Map<String, Set<String>> JDK_SUPERTYPES = Map.ofEntries(
            Map.entry("String", Set.of("CharSequence", "Comparable", "Serializable")),
            Map.entry("Boolean", Set.of("Comparable", "Serializable")),
            Map.entry("Character", Set.of("Comparable", "Serializable")),
            Map.entry("Byte", Set.of("Number", "Comparable", "Serializable")),
            Map.entry("Short", Set.of("Number", "Comparable", "Serializable")),
            Map.entry("Integer", Set.of("Number", "Comparable", "Serializable")),
            Map.entry("Long", Set.of("Number", "Comparable", "Serializable")),
            Map.entry("Float", Set.of("Number", "Comparable", "Serializable")),
            Map.entry("Double", Set.of("Number", "Comparable", "Serializable")),
            Map.entry("Number", Set.of("Serializable")),
            Map.entry("Class", Set.of("Serializable")),
            Map.entry("Object", Set.of()),
            Map.entry("CharSequence", Set.of()),
            Map.entry("Comparable", Set.of()),
            Map.entry("Serializable", Set.of()));
    /** JDK classes no repository type can extend. */
    private static final Set<String> FINAL_JDK = Set.of("String", "Boolean", "Character", "Byte", "Short", "Integer",
            "Long", "Float", "Double", "Class");
    private static final int MAX_HIERARCHY_DEPTH = 24;

    private final SymbolIndex index;
    private final TypeResolver types;
    private final Map<UUID, Params> paramCache = new HashMap<>();

    OverloadFilter(SymbolIndex index, TypeResolver types) {
        this.index = index;
        this.types = types;
    }

    /**
     * @param candidates methods or constructors that accept the call's argument count
     * @param hints      one hint per argument, {@code ""} when unknown
     * @return the candidates that may still be the target (never empty; the input when nothing can be decided)
     */
    List<Node> narrow(List<Node> candidates, List<String> hints, FileContext callerCtx, List<Node> callerChain) {
        if (candidates.size() < 2 || hints.stream().allMatch(String::isEmpty)) return candidates;

        List<Ty> args = new ArrayList<>(hints.size());
        for (String h : hints) args.add(classify(h, callerCtx, callerChain));

        int n = candidates.size();
        Fit[][] fits = new Fit[4][n];
        for (int i = 0; i < n; i++) {
            Params p = params(candidates.get(i));
            for (int phase = 1; phase <= 3; phase++) fits[phase][i] = fit(p, args, phase);
        }

        int selected = 0; // lowest phase in which some candidate is certainly applicable
        for (int phase = 1; phase <= 3 && selected == 0; phase++) {
            for (int i = 0; i < n; i++) if (fits[phase][i] == Fit.YES) selected = phase;
        }
        int lastPhase = selected == 0 ? 3 : selected;

        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int phase = 1; phase <= lastPhase; phase++) {
                if (fits[phase][i] != Fit.NO) {
                    keep.add(i);
                    break;
                }
            }
        }
        if (selected != 0 && selected < 3) keep = dropLessSpecific(candidates, keep, fits[selected], selected == 2);
        if (keep.isEmpty()) return candidates; // nothing fits what the text shows: stay with the arity-based result

        List<Node> narrowed = new ArrayList<>(keep.size());
        for (int i : keep) narrowed.add(candidates.get(i));
        return narrowed;
    }

    /** Removes candidates that a certainly applicable candidate is strictly more specific than. */
    private List<Integer> dropLessSpecific(List<Node> candidates, List<Integer> keep, Fit[] fitsAtPhase, boolean loose) {
        List<Integer> out = new ArrayList<>();
        for (int b : keep) {
            boolean dominated = false;
            for (int a : keep) {
                if (a != b && fitsAtPhase[a] == Fit.YES
                        && moreSpecific(candidates.get(a), candidates.get(b), loose)
                        && !moreSpecific(candidates.get(b), candidates.get(a), loose)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) out.add(b);
        }
        return out;
    }

    private boolean moreSpecific(Node a, Node b, boolean loose) {
        List<Ty> pa = params(a).types();
        List<Ty> pb = params(b).types();
        if (pa.size() != pb.size()) return false;
        for (int i = 0; i < pa.size(); i++) {
            if (argFit(pa.get(i), pb.get(i), loose) != Fit.YES) return false;
        }
        return true;
    }

    // ---- applicability ----------------------------------------------------------------------------------------------

    private Fit fit(Params p, List<Ty> args, int phase) {
        List<Ty> declared = p.types();
        Fit result = Fit.YES;
        if (phase < 3) {
            if (declared.size() != args.size()) return Fit.NO;
            for (int i = 0; i < args.size(); i++) result = worst(result, argFit(args.get(i), declared.get(i), phase == 2));
            return result;
        }
        if (!p.varargs()) return Fit.NO;
        int fixed = declared.size() - 1;
        if (args.size() < fixed) return Fit.NO;
        for (int i = 0; i < fixed; i++) result = worst(result, argFit(args.get(i), declared.get(i), true));
        Ty element = declared.get(fixed).element();
        for (int i = fixed; i < args.size(); i++) result = worst(result, argFit(args.get(i), element, true));
        return result;
    }

    private static Fit worst(Fit a, Fit b) {
        if (a == Fit.NO || b == Fit.NO) return Fit.NO;
        return a == Fit.UNKNOWN || b == Fit.UNKNOWN ? Fit.UNKNOWN : Fit.YES;
    }

    /** Can an argument of type {@code a} be passed to a parameter of type {@code p}? {@code loose} allows boxing. */
    private Fit argFit(Ty a, Ty p, boolean loose) {
        if (a.kind() == Kind.UNKNOWN || p.kind() == Kind.UNKNOWN) return Fit.UNKNOWN;
        if (a.kind() == Kind.NULL) return p.kind() == Kind.PRIMITIVE ? Fit.NO : Fit.YES;
        if (a.kind() == Kind.PRIMITIVE) {
            if (p.kind() == Kind.PRIMITIVE) return primitiveFit(a.name(), p.name());
            if (!loose) return Fit.NO;
            return referenceFit(new Ty(Kind.JDK, BOX.get(a.name()), null, null), p);
        }
        if (p.kind() == Kind.PRIMITIVE) {
            if (!loose) return Fit.NO;
            String unboxed = a.kind() == Kind.JDK ? UNBOX.get(a.name()) : null;
            return unboxed != null ? primitiveFit(unboxed, p.name()) : Fit.NO;
        }
        return referenceFit(a, p);
    }

    private static Fit primitiveFit(String from, String to) {
        return from.equals(to) || WIDENING.getOrDefault(from, Set.of()).contains(to) ? Fit.YES : Fit.NO;
    }

    private Fit referenceFit(Ty a, Ty p) {
        if (p.kind() == Kind.JDK && p.name().equals("Object")) return Fit.YES;
        switch (a.kind()) {
            case ARRAY:
                if (p.kind() == Kind.ARRAY) return arrayElementFit(a.element(), p.element());
                if (p.kind() == Kind.JDK) return p.name().equals("Serializable") ? Fit.YES : Fit.NO;
                return p.kind() == Kind.EXTERNAL ? Fit.UNKNOWN : Fit.NO;
            case REPO:
                if (p.kind() == Kind.REPO) return repoSubtype(a.node(), p.node());
                if (p.kind() == Kind.JDK) {
                    return FINAL_JDK.contains(p.name()) ? Fit.NO : (hierarchyIncomplete(a.node()) ? Fit.UNKNOWN : Fit.NO);
                }
                return p.kind() == Kind.EXTERNAL ? Fit.UNKNOWN : Fit.NO;
            case JDK:
                if (p.kind() == Kind.JDK) {
                    return a.name().equals(p.name()) || JDK_SUPERTYPES.getOrDefault(a.name(), Set.of()).contains(p.name())
                            ? Fit.YES : Fit.NO;
                }
                return p.kind() == Kind.EXTERNAL ? Fit.UNKNOWN : Fit.NO;
            case EXTERNAL:
                if (p.kind() == Kind.JDK) return FINAL_JDK.contains(p.name()) ? Fit.NO : Fit.UNKNOWN;
                return p.kind() == Kind.ARRAY ? Fit.NO : Fit.UNKNOWN;
            default:
                return Fit.UNKNOWN;
        }
    }

    private Fit arrayElementFit(Ty a, Ty p) {
        if (a.kind() == Kind.UNKNOWN || p.kind() == Kind.UNKNOWN) return Fit.UNKNOWN;
        if (a.kind() == Kind.PRIMITIVE || p.kind() == Kind.PRIMITIVE) {
            return a.kind() == p.kind() && a.name().equals(p.name()) ? Fit.YES : Fit.NO; // int[] is not a long[]
        }
        return referenceFit(a, p);
    }

    private Fit repoSubtype(Node a, Node p) {
        if (a.qualifiedName().equals(p.qualifiedName())) return Fit.YES;
        Set<UUID> ancestors = ancestorsOf(a);
        for (UUID id : ancestors) {
            Node t = index.nodeById.get(id);
            if (t != null && t.qualifiedName().equals(p.qualifiedName())) return Fit.YES;
        }
        return hierarchyIncomplete(a) ? Fit.UNKNOWN : Fit.NO;
    }

    private boolean hierarchyIncomplete(Node type) {
        if (index.incompleteSupertypes.contains(type.id())) return true;
        for (UUID id : ancestorsOf(type)) if (index.incompleteSupertypes.contains(id)) return true;
        return false;
    }

    /** Transitive repository supertypes, bounded. */
    private Set<UUID> ancestorsOf(Node type) {
        Set<UUID> seen = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>(index.supertypesOf(type));
        for (int steps = 0; !queue.isEmpty() && steps < MAX_HIERARCHY_DEPTH * 8; steps++) {
            Node t = queue.poll();
            if (seen.add(t.id())) queue.addAll(index.supertypesOf(t));
        }
        return seen;
    }

    // ---- types ------------------------------------------------------------------------------------------------------

    private Params params(Node method) {
        return paramCache.computeIfAbsent(method.id(), id -> {
            FileContext ctx = index.files.get(method.path());
            List<Node> chain = index.enclosingTypes(method);
            List<String> texts = JavaSignatures.parameters(method.signature());
            List<Ty> out = new ArrayList<>(texts.size());
            for (String t : texts) out.add(ctx == null ? Ty.UNKNOWN : classify(t, ctx, chain));
            return new Params(out, !texts.isEmpty() && texts.get(texts.size() - 1).trim().endsWith("..."));
        });
    }

    /** Classifies a type as written (a hint or a declared parameter type) in the given file and scope. */
    private Ty classify(String text, FileContext ctx, List<Node> chain) {
        String t = text.trim();
        if (t.isEmpty()) return Ty.UNKNOWN;
        if (t.equals("null")) return Ty.NULL;
        int dims = 0;
        if (t.endsWith("...")) {
            t = t.substring(0, t.length() - 3).trim();
            dims++;
        }
        while (t.endsWith("[]")) {
            t = t.substring(0, t.length() - 2).trim();
            dims++;
        }
        t = stripGenerics(t);
        if (t.startsWith("java.lang.") && t.indexOf('.', 10) < 0) t = t.substring(10);
        Ty base = classifyElement(t, ctx, chain);
        for (int i = 0; i < dims; i++) {
            base = base.kind() == Kind.UNKNOWN ? Ty.UNKNOWN : new Ty(Kind.ARRAY, base.name() + "[]", null, base);
        }
        return base;
    }

    private Ty classifyElement(String name, FileContext ctx, List<Node> chain) {
        if (name.isEmpty()) return Ty.UNKNOWN;
        if (PRIMITIVES.contains(name)) return new Ty(Kind.PRIMITIVE, name, null, null);
        TypeResolver.Resolved rt = types.resolve(name, ctx, chain);
        if (rt.state() == Resolution.RESOLVED) return new Ty(Kind.REPO, rt.single().qualifiedName(), rt.single(), null);
        if (rt.state() == Resolution.AMBIGUOUS) return Ty.UNKNOWN;
        return JDK_SUPERTYPES.containsKey(name) ? new Ty(Kind.JDK, name, null, null) : new Ty(Kind.EXTERNAL, name, null, null);
    }

    private static String stripGenerics(String t) {
        if (t.indexOf('<') < 0) return t;
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : t.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (depth == 0) sb.append(c);
        }
        return sb.toString().trim();
    }
}
