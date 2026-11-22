package dev.codetrace.ingestion.parser;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor.Endpoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * HTTP endpoints declared with Spring MVC ({@code @RequestMapping}, {@code @GetMapping}, ...) or JAX-RS ({@code @Path},
 * {@code @GET}, ...) annotations, read from the syntax only. Routes are joined from the type-level and method-level paths.
 * A path or HTTP method that is not written as a literal cannot be known without running the code, so such an endpoint
 * is kept with an empty route or method {@code "?"} and a reason, rather than guessed.
 */
final class EndpointExtractor {
    static final String SPRING = "SPRING_MVC";
    static final String JAX_RS = "JAX_RS";
    static final String ROUTE_NOT_LITERAL = "ROUTE_NOT_LITERAL";
    static final String METHOD_NOT_LITERAL = "METHOD_NOT_LITERAL";

    private static final Map<String, String> SPRING_SHORTCUTS = Map.of("GetMapping", "GET", "PostMapping", "POST",
            "PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH");
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    private EndpointExtractor() {
    }

    /** The path values of one annotation; {@code known} is false when any of them is not a literal. */
    private record Paths(List<String> values, boolean known) {
        static final Paths ROOT = new Paths(List.of(""), true);
        static final Paths UNKNOWN = new Paths(List.of(), false);
    }

    /** The endpoints a method of {@code owner} declares. */
    static List<Endpoint> of(MethodDeclaration m, String handlerQn, TypeDeclaration<?> owner) {
        List<Endpoint> out = new ArrayList<>();
        for (AnnotationExpr a : m.getAnnotations()) {
            String name = a.getName().getIdentifier();
            if (SPRING_SHORTCUTS.containsKey(name) || name.equals("RequestMapping")) {
                Paths prefix = annotation(owner, "RequestMapping").map(EndpointExtractor::springPaths).orElse(Paths.ROOT);
                List<String> methods = SPRING_SHORTCUTS.containsKey(name) ? List.of(SPRING_SHORTCUTS.get(name)) : springMethods(a);
                emit(out, handlerQn, SPRING, methods, prefix, springPaths(a), line(a));
            }
        }
        List<String> jaxMethods = m.getAnnotations().stream().map(a -> a.getName().getIdentifier())
                .filter(HTTP_METHODS::contains).toList();
        if (!jaxMethods.isEmpty()) {
            Paths prefix = annotation(owner, "Path").map(EndpointExtractor::valuePaths).orElse(Paths.ROOT);
            Paths own = m.getAnnotationByName("Path").map(EndpointExtractor::valuePaths).orElse(Paths.ROOT);
            int line = m.getAnnotations().stream().filter(a -> HTTP_METHODS.contains(a.getName().getIdentifier()))
                    .findFirst().map(EndpointExtractor::line).orElse(0);
            emit(out, handlerQn, JAX_RS, jaxMethods, prefix, own, line);
        }
        return out;
    }

    private static void emit(List<Endpoint> out, String handlerQn, String framework, List<String> methods, Paths prefix,
                             Paths own, int line) {
        boolean methodKnown = !methods.isEmpty() && !methods.contains("?");
        List<String> httpMethods = methods.isEmpty() ? List.of("?") : methods;
        for (String method : httpMethods) {
            if (!prefix.known() || !own.known()) {
                out.add(new Endpoint(handlerQn, method, "", framework, ROUTE_NOT_LITERAL, line));
                continue;
            }
            for (String p : prefix.values()) {
                for (String o : own.values()) {
                    out.add(new Endpoint(handlerQn, method, join(p, o), framework, methodKnown ? "" : METHOD_NOT_LITERAL, line));
                }
            }
        }
    }

    /** {@code /a} + {@code b/} -> {@code /a/b}; the root route is {@code /}. */
    static String join(String prefix, String path) {
        String joined = ("/" + prefix + "/" + path).replaceAll("/+", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }

    private static Optional<AnnotationExpr> annotation(TypeDeclaration<?> type, String name) {
        return type.getAnnotations().stream().filter(a -> a.getName().getIdentifier().equals(name)).findFirst();
    }

    /** Spring paths come from {@code value} or {@code path}; no attribute means the type's own route. */
    private static Paths springPaths(AnnotationExpr a) {
        if (a instanceof SingleMemberAnnotationExpr s) return strings(s.getMemberValue());
        if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                if (p.getNameAsString().equals("value") || p.getNameAsString().equals("path")) return strings(p.getValue());
            }
        }
        return Paths.ROOT;
    }

    private static Paths valuePaths(AnnotationExpr a) {
        if (a instanceof SingleMemberAnnotationExpr s) return strings(s.getMemberValue());
        if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                if (p.getNameAsString().equals("value")) return strings(p.getValue());
            }
        }
        return Paths.ROOT;
    }

    /** {@code method = RequestMethod.GET} or {@code {GET, POST}}; none means every method. */
    private static List<String> springMethods(AnnotationExpr a) {
        if (!(a instanceof NormalAnnotationExpr n)) return List.of("ANY");
        for (MemberValuePair p : n.getPairs()) {
            if (!p.getNameAsString().equals("method")) continue;
            List<Expression> values = p.getValue() instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(p.getValue());
            List<String> out = new ArrayList<>();
            for (Expression v : values) {
                String name = v instanceof FieldAccessExpr f ? f.getNameAsString() : v instanceof NameExpr ne ? ne.getNameAsString() : "";
                out.add(HTTP_METHODS.contains(name) ? name : "?");
            }
            return out.isEmpty() ? List.of("ANY") : out;
        }
        return List.of("ANY");
    }

    /** One or more string literals, possibly concatenated; anything else is not a literal. */
    private static Paths strings(Expression e) {
        if (e instanceof ArrayInitializerExpr arr) {
            List<String> values = new ArrayList<>();
            for (Expression v : arr.getValues()) {
                String s = literal(v);
                if (s == null) return Paths.UNKNOWN;
                values.add(s);
            }
            return values.isEmpty() ? Paths.ROOT : new Paths(values, true);
        }
        String s = literal(e);
        return s == null ? Paths.UNKNOWN : new Paths(List.of(s), true);
    }

    private static String literal(Expression e) {
        if (e instanceof StringLiteralExpr s) return s.getValue();
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            String l = literal(b.getLeft());
            String r = literal(b.getRight());
            return l == null || r == null ? null : l + r;
        }
        return null;
    }

    private static int line(AnnotationExpr a) {
        return a.getBegin().map(p -> p.line).orElse(0);
    }
}
