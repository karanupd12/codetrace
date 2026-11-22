package dev.codetrace.ingestion.parser;

import dev.codetrace.common.util.Texts;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import dev.codetrace.jobs.model.IndexedRevision;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure syntactic extraction of Java declarations and raw reference facts. Parses text only: it never compiles, loads,
 * or executes code.
 *
 * <p>Identity: types are {@code pkg.Outer.Inner}; members are {@code pkg.Outer.Inner#name(ParamTypes)} where
 * parameter types are the textual types as written (generics kept, varargs as {@code ...}).
 *
 * <p>References are facts about the text ("this method contains a call to {@code foo} with 2 arguments on a receiver
 * declared as {@code Order}"). Resolving them to declarations, with an explicit resolution state, is the graph
 * builder's job; nothing here claims a resolved relationship.
 */
public final class JavaSymbolExtractor {
    public static final String PARSER_VERSION = "javaparser-core-3.26.4";
    public static final String EXTRACTION_METHOD = "java-declarations-v4";
    /** The version of the facts this extractor produces (4: HTTP endpoints); see {@link IndexedRevision}. */
    public static final int EXTRACTION_VERSION = IndexedRevision.CURRENT_EXTRACTION_VERSION;
    private static final int MAX_MESSAGE = 300;

    public enum FileStatus { PARSED, PARTIAL, FAILED }

    public record Symbol(String kind, String subtype, String simpleName, String qualifiedName, String signature,
                         String parentQualifiedName, String modifiers, int startLine, int endLine) {
    }

    public record Import(String name, boolean isStatic, boolean wildcard, int line) {
    }

    public record Diagnostic(String severity, String code, String message, int line) {
    }

    /**
     * An unresolved reference made by the declaration {@code ownerQualifiedName}.
     *
     * @param kind         EXTENDS, IMPLEMENTS, REFERENCES (type usage) or CALLS
     * @param name         type name as written (e.g. {@code Map.Entry}) or the called method name ({@code <init>} for constructors)
     * @param argCount     number of call arguments, or -1 for type references
     * @param receiverKind NONE (unqualified call), THIS, SUPER, TYPE (receiver type text known) or UNKNOWN
     * @param receiverType the receiver's type text when {@code receiverKind == TYPE}, else empty
     * @param argTypes     one type hint per call argument as written in the caller (a primitive name, {@code String},
     *                     a type name, {@code T[]} or {@code null}); {@code ""} when the text does not reveal the
     *                     argument's type; empty for type references
     */
    public record Reference(String ownerQualifiedName, String kind, String name, int argCount, String receiverKind,
                            String receiverType, List<String> argTypes, int line) {
    }

    /**
     * An HTTP endpoint declared by the method {@code handlerQualifiedName}.
     *
     * @param httpMethod GET, POST, ..., ANY when the mapping accepts every method, or {@code ?} when not a literal
     * @param route      the joined route, e.g. {@code /orders/{id}}; empty when a path is not a literal
     * @param framework  SPRING_MVC or JAX_RS
     * @param reason     why part of the endpoint is unknown (ROUTE_NOT_LITERAL, METHOD_NOT_LITERAL), else empty
     */
    public record Endpoint(String handlerQualifiedName, String httpMethod, String route, String framework, String reason,
                           int line) {
    }

    public record FileExtraction(FileStatus status, String packageName, List<Import> imports, List<Symbol> symbols,
                                 List<Reference> references, List<Diagnostic> diagnostics, List<Endpoint> endpoints) {
    }

    private record Receiver(String kind, String type) {
        static final Receiver NONE = new Receiver("NONE", "");
        static final Receiver THIS = new Receiver("THIS", "");
        static final Receiver SUPER = new Receiver("SUPER", "");
        static final Receiver UNKNOWN = new Receiver("UNKNOWN", "");

        static Receiver type(String text) {
            return new Receiver("TYPE", text);
        }
    }

    private final JavaParser parser;

    public JavaSymbolExtractor() {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false); // tokens must stay stored: source ranges (line numbers) derive from them
        this.parser = new JavaParser(config);
    }

    /** Never throws for bad input; failures become diagnostics. Not thread-safe (JavaParser instance is shared). */
    public FileExtraction extract(String source) {
        ParseResult<CompilationUnit> result;
        try {
            result = parser.parse(source);
        } catch (RuntimeException | StackOverflowError e) {
            return new FileExtraction(FileStatus.FAILED, "", List.of(), List.of(), List.of(),
                    List.of(new Diagnostic("ERROR", "PARSER_CRASH", e.getClass().getSimpleName(), 0)), List.of());
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Problem p : result.getProblems()) {
            int line = p.getLocation().flatMap(l -> l.getBegin().getRange()).map(r -> r.begin.line).orElse(0);
            diagnostics.add(new Diagnostic("ERROR", "SYNTAX_ERROR", truncate(p.getMessage()), line));
        }
        if (result.getResult().isEmpty()) {
            return new FileExtraction(FileStatus.FAILED, "", List.of(), List.of(), List.of(), diagnostics, List.of());
        }

        CompilationUnit cu = result.getResult().get();
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        List<Import> imports = new ArrayList<>();
        for (ImportDeclaration i : cu.getImports()) {
            String name = i.getNameAsString() + (i.isAsterisk() ? ".*" : "");
            imports.add(new Import(name, i.isStatic(), i.isAsterisk(), line(i)));
        }
        List<Symbol> symbols = new ArrayList<>();
        List<Reference> references = new ArrayList<>();
        List<Endpoint> endpoints = new ArrayList<>();
        try {
            for (TypeDeclaration<?> t : cu.getTypes()) {
                visitType(t, pkg.isEmpty() ? null : pkg, symbols, references, endpoints, Set.of(), List.of());
            }
        } catch (RuntimeException | StackOverflowError e) {
            diagnostics.add(new Diagnostic("ERROR", "EXTRACTION_ERROR", e.getClass().getSimpleName(), 0));
            return new FileExtraction(FileStatus.PARTIAL, pkg, imports, symbols, references, diagnostics, endpoints);
        }
        FileStatus status = diagnostics.isEmpty() ? FileStatus.PARSED : FileStatus.PARTIAL;
        return new FileExtraction(status, pkg, imports, symbols, references, diagnostics, endpoints);
    }

    // ---- declarations ---------------------------------------------------------------------------------------

    private void visitType(TypeDeclaration<?> type, String parentQn, List<Symbol> out, List<Reference> refs,
                           List<Endpoint> endpoints, Set<String> outerTypeParams, List<TypeDeclaration<?>> outerChain) {
        String qn = parentQn == null ? type.getNameAsString() : parentQn + "." + type.getNameAsString();
        out.add(new Symbol("TYPE", typeSubtype(type), type.getNameAsString(), qn, "", parentOf(parentQn),
                modifiers(type), line(type), endLine(type)));

        Set<String> typeParams = withTypeParams(outerTypeParams, type);
        List<TypeDeclaration<?>> chain = new ArrayList<>();
        chain.add(type);
        chain.addAll(outerChain);

        collectHeaderReferences(type, qn, typeParams, refs);

        if (type instanceof EnumDeclaration e) {
            for (EnumConstantDeclaration c : e.getEntries()) {
                String cqn = qn + "#" + c.getNameAsString();
                out.add(new Symbol("FIELD", "ENUM_CONSTANT", c.getNameAsString(), cqn, "", qn, "", line(c), endLine(c)));
                collect(c, cqn, typeParams, chain, c, refs);
            }
        }
        List<String> recordParamTypes = List.of();
        if (type instanceof RecordDeclaration r) {
            recordParamTypes = r.getParameters().stream().map(JavaSymbolExtractor::paramType).toList();
            for (Parameter p : r.getParameters()) {
                String pqn = qn + "#" + p.getNameAsString();
                out.add(new Symbol("FIELD", "RECORD_COMPONENT", p.getNameAsString(), pqn, "", qn, "", line(p), endLine(p)));
                collectTypes(p.getType(), pqn, typeParams, refs);
            }
        }

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                visitType(nested, qn, out, refs, endpoints, typeParams, chain);
            } else if (member instanceof MethodDeclaration m) {
                Symbol s = callable(m, m.getNameAsString(), qn);
                out.add(s);
                endpoints.addAll(EndpointExtractor.of(m, s.qualifiedName(), type));
                collect(m, s.qualifiedName(), withTypeParams(typeParams, m), chain, m, refs);
            } else if (member instanceof ConstructorDeclaration c) {
                Symbol s = callable(c, "<init>", qn);
                out.add(s);
                collect(c, s.qualifiedName(), withTypeParams(typeParams, c), chain, c, refs);
            } else if (member instanceof CompactConstructorDeclaration c) {
                String sig = "<init>(" + String.join(",", recordParamTypes) + ")";
                out.add(new Symbol("METHOD", "CONSTRUCTOR", type.getNameAsString(), qn + "#" + sig, sig, qn,
                        modifiers(c), line(c), endLine(c)));
                collect(c, qn + "#" + sig, typeParams, chain, c, refs);
            } else if (member instanceof FieldDeclaration f) {
                for (VariableDeclarator v : f.getVariables()) {
                    String fqn = qn + "#" + v.getNameAsString();
                    out.add(new Symbol("FIELD", "FIELD", v.getNameAsString(), fqn, "", qn, modifiers(f), line(f), endLine(f)));
                    collectTypes(v.getType(), fqn, typeParams, refs);
                    v.getInitializer().ifPresent(init -> collect(init, fqn, typeParams, chain, v, refs));
                }
            } else if (member instanceof AnnotationMemberDeclaration a) {
                String sig = a.getNameAsString() + "()";
                out.add(new Symbol("METHOD", "ANNOTATION_MEMBER", a.getNameAsString(), qn + "#" + sig, sig, qn,
                        modifiers(a), line(a), endLine(a)));
                collectTypes(a.getType(), qn + "#" + sig, typeParams, refs);
            } else if (member instanceof InitializerDeclaration init) {
                collect(init, qn, typeParams, chain, init, refs); // static/instance init blocks belong to the type
            }
            // Local and anonymous classes are not addressable declarations; their contents belong to the enclosing member.
        }
    }

    private static Symbol callable(CallableDeclaration<?> c, String name, String ownerQn) {
        String params = c.getParameters().stream().map(JavaSymbolExtractor::paramType).collect(Collectors.joining(","));
        String sig = name + "(" + params + ")";
        String subtype = c instanceof ConstructorDeclaration ? "CONSTRUCTOR" : "METHOD";
        return new Symbol("METHOD", subtype, c instanceof ConstructorDeclaration ? ownerSimple(ownerQn) : name,
                ownerQn + "#" + sig, sig, ownerQn, modifiers(c), line(c), endLine(c));
    }

    // ---- references -----------------------------------------------------------------------------------------

    /** extends / implements clauses, plus type arguments inside them. */
    private static void collectHeaderReferences(TypeDeclaration<?> type, String qn, Set<String> typeParams,
                                                List<Reference> refs) {
        List<ClassOrInterfaceType> extended = List.of();
        List<ClassOrInterfaceType> implemented = List.of();
        if (type instanceof ClassOrInterfaceDeclaration c) {
            extended = c.getExtendedTypes();
            implemented = c.getImplementedTypes();
        } else if (type instanceof EnumDeclaration e) {
            implemented = e.getImplementedTypes();
        } else if (type instanceof RecordDeclaration r) {
            implemented = r.getImplementedTypes();
        }
        for (ClassOrInterfaceType t : extended) {
            addTypeRef(refs, qn, "EXTENDS", t, typeParams);
            collectTypeArguments(t, qn, typeParams, refs);
        }
        for (ClassOrInterfaceType t : implemented) {
            addTypeRef(refs, qn, "IMPLEMENTS", t, typeParams);
            collectTypeArguments(t, qn, typeParams, refs);
        }
    }

    private static void collectTypeArguments(ClassOrInterfaceType t, String owner, Set<String> typeParams,
                                             List<Reference> refs) {
        t.getTypeArguments().ifPresent(args -> args.forEach(a -> collectTypes(a, owner, typeParams, refs)));
    }

    /** Type usages (REFERENCES) anywhere under {@code root}. */
    private static void collectTypes(Node root, String owner, Set<String> typeParams, List<Reference> refs) {
        for (ClassOrInterfaceType t : root.findAll(ClassOrInterfaceType.class)) {
            boolean isScopeOfAnother = t.getParentNode()
                    .filter(p -> p instanceof ClassOrInterfaceType cp && cp.getScope().filter(s -> s == t).isPresent())
                    .isPresent();
            if (!isScopeOfAnother) {
                addTypeRef(refs, owner, "REFERENCES", t, typeParams);
            }
        }
    }

    private static void addTypeRef(List<Reference> refs, String owner, String kind, ClassOrInterfaceType t,
                                   Set<String> typeParams) {
        String name = t.getNameWithScope();
        String first = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (typeParams.contains(first)) return; // a generic type variable, not a declared type
        refs.add(new Reference(owner, kind, name, -1, "NONE", "", List.of(), line(t)));
    }

    /** All type usages and calls inside one member. {@code searchScope} bounds local-variable type lookups. */
    private static void collect(Node root, String owner, Set<String> typeParams, List<TypeDeclaration<?>> chain,
                                Node searchScope, List<Reference> refs) {
        if (!(root instanceof VariableDeclarator)) {
            collectTypes(root, owner, typeParams, refs);
        }
        for (MethodCallExpr call : root.findAll(MethodCallExpr.class)) {
            Receiver r = call.getScope().map(s -> receiverOf(s, call, searchScope, chain, typeParams))
                    .orElse(Receiver.NONE);
            refs.add(new Reference(owner, "CALLS", call.getNameAsString(), call.getArguments().size(), r.kind(),
                    r.type(), argumentTypes(call.getArguments(), call, searchScope, chain, typeParams), line(call)));
        }
        for (ObjectCreationExpr n : root.findAll(ObjectCreationExpr.class)) {
            String type = n.getType().getNameWithScope();
            String first = type.contains(".") ? type.substring(0, type.indexOf('.')) : type;
            Receiver r = typeParams.contains(first) ? Receiver.UNKNOWN : Receiver.type(type);
            refs.add(new Reference(owner, "CALLS", "<init>", n.getArguments().size(), r.kind(), r.type(),
                    argumentTypes(n.getArguments(), n, searchScope, chain, typeParams), line(n)));
        }
        for (ExplicitConstructorInvocationStmt s : root.findAll(ExplicitConstructorInvocationStmt.class)) {
            Receiver r = s.isThis() ? Receiver.THIS : Receiver.SUPER;
            refs.add(new Reference(owner, "CALLS", "<init>", s.getArguments().size(), r.kind(), "",
                    argumentTypes(s.getArguments(), s, searchScope, chain, typeParams), line(s)));
        }
    }

    private static Receiver receiverOf(Expression scope, Node call, Node searchScope,
                                       List<TypeDeclaration<?>> chain, Set<String> typeParams) {
        Expression s = scope;
        while (s instanceof EnclosedExpr e) s = e.getInner();

        if (s instanceof ThisExpr t) return t.getTypeName().isPresent() ? Receiver.UNKNOWN : Receiver.THIS;
        if (s instanceof SuperExpr t) return t.getTypeName().isPresent() ? Receiver.UNKNOWN : Receiver.SUPER;
        if (s instanceof ObjectCreationExpr n) return typed(n.getType().getNameWithScope(), typeParams);
        if (s instanceof CastExpr c) {
            return c.getType() instanceof ClassOrInterfaceType ct ? typed(ct.getNameWithScope(), typeParams)
                    : Receiver.UNKNOWN;
        }
        if (s instanceof NameExpr n) {
            String name = n.getNameAsString();
            Optional<Receiver> local = declaredTypeInScope(name, searchScope, call, typeParams);
            if (local.isPresent()) return local.get();
            Optional<Receiver> field = fieldType(name, chain, typeParams);
            if (field.isPresent()) return field.get();
            return Character.isUpperCase(name.charAt(0)) ? Receiver.type(name) : Receiver.UNKNOWN;
        }
        if (s instanceof FieldAccessExpr fa) {
            if (fa.getScope() instanceof ThisExpr te && te.getTypeName().isEmpty()) {
                return fieldType(fa.getNameAsString(), chain, typeParams).orElse(Receiver.UNKNOWN);
            }
            String chainText = dottedName(fa);
            if (chainText != null) {
                String last = chainText.substring(chainText.lastIndexOf('.') + 1);
                if (!last.isEmpty() && Character.isUpperCase(last.charAt(0))) return Receiver.type(chainText);
            }
        }
        return Receiver.UNKNOWN;
    }

    private static Receiver typed(String typeText, Set<String> typeParams) {
        String first = typeText.contains(".") ? typeText.substring(0, typeText.indexOf('.')) : typeText;
        return typeParams.contains(first) ? Receiver.UNKNOWN : Receiver.type(typeText);
    }

    /** {@code a.b.C} for a pure name/field-access chain, otherwise null. */
    private static String dottedName(Expression e) {
        if (e instanceof NameExpr n) return n.getNameAsString();
        if (e instanceof FieldAccessExpr f) {
            String left = dottedName(f.getScope());
            return left == null ? null : left + "." + f.getNameAsString();
        }
        return null;
    }

    /** Declared (explicit) type of a local variable or parameter named {@code name} visible before {@code call}. */
    private static Optional<Receiver> declaredTypeInScope(String name, Node searchScope, Node call,
                                                          Set<String> typeParams) {
        return declaredVariableType(name, searchScope, call)
                .map(t -> t instanceof ClassOrInterfaceType ct ? typed(ct.getNameWithScope(), typeParams)
                        : Receiver.UNKNOWN); // var, arrays, primitives, lambda params without a type
    }

    private static Optional<Type> declaredVariableType(String name, Node searchScope, Node call) {
        Position callPos = call.getBegin().orElse(null);
        Type best = null;
        boolean found = false;
        Position bestPos = null;
        for (VariableDeclarator v : searchScope.findAll(VariableDeclarator.class)) {
            if (v.getNameAsString().equals(name) && v.getBegin().isPresent()
                    && (callPos == null || v.getBegin().get().isBefore(callPos))
                    && (bestPos == null || v.getBegin().get().isAfter(bestPos))) {
                best = v.getType();
                bestPos = v.getBegin().get();
                found = true;
            }
        }
        for (Parameter p : searchScope.findAll(Parameter.class)) {
            if (p.getNameAsString().equals(name) && p.getBegin().isPresent()
                    && (callPos == null || p.getBegin().get().isBefore(callPos))
                    && (bestPos == null || p.getBegin().get().isAfter(bestPos))) {
                best = p.getType();
                bestPos = p.getBegin().get();
                found = true;
            }
        }
        return found ? Optional.of(best) : Optional.empty();
    }

    private static Optional<Receiver> fieldType(String name, List<TypeDeclaration<?>> chain, Set<String> typeParams) {
        return declaredFieldType(name, chain).map(t -> t instanceof ClassOrInterfaceType ct
                ? typed(ct.getNameWithScope(), typeParams) : Receiver.UNKNOWN);
    }

    private static Optional<Type> declaredFieldType(String name, List<TypeDeclaration<?>> chain) {
        for (TypeDeclaration<?> t : chain) {
            for (FieldDeclaration f : t.getFields()) {
                for (VariableDeclarator v : f.getVariables()) {
                    if (v.getNameAsString().equals(name)) return Optional.of(v.getType());
                }
            }
        }
        return Optional.empty();
    }

    // ---- argument type hints ------------------------------------------------------------------------------------

    private static List<String> argumentTypes(List<Expression> args, Node call, Node searchScope,
                                              List<TypeDeclaration<?>> chain, Set<String> typeParams) {
        List<String> hints = new ArrayList<>(args.size());
        for (Expression a : args) hints.add(argumentType(a, call, searchScope, chain, typeParams));
        return hints;
    }

    /**
     * What the text says about an argument's static type, or {@code ""} when it says nothing reliable. Only literals,
     * constructions, casts, declared variables and fields, {@code this}, string concatenation and comparisons are
     * typed; the result of a method call, a lambda or a conditional expression is not.
     */
    private static String argumentType(Expression e, Node call, Node searchScope, List<TypeDeclaration<?>> chain,
                                       Set<String> typeParams) {
        while (e instanceof EnclosedExpr enclosed) e = enclosed.getInner();
        if (e instanceof BooleanLiteralExpr) return "boolean";
        if (e instanceof CharLiteralExpr) return "char";
        if (e instanceof IntegerLiteralExpr) return "int";
        if (e instanceof LongLiteralExpr) return "long";
        if (e instanceof DoubleLiteralExpr d) {
            String v = d.getValue();
            boolean hex = v.startsWith("0x") || v.startsWith("0X");
            return !hex && (v.endsWith("f") || v.endsWith("F")) ? "float" : "double";
        }
        if (e instanceof StringLiteralExpr || e instanceof TextBlockLiteralExpr) return "String";
        if (e instanceof NullLiteralExpr) return "null";
        if (e instanceof ClassExpr) return "Class";
        if (e instanceof ObjectCreationExpr n) {
            return n.getAnonymousClassBody().isPresent() ? "" : typeHint(n.getType().getNameWithScope(), typeParams);
        }
        if (e instanceof CastExpr c) return typeHint(c.getType(), typeParams);
        if (e instanceof ThisExpr t) {
            if (t.getTypeName().isPresent()) return typeHint(t.getTypeName().get().asString(), typeParams);
            return chain.isEmpty() ? "" : chain.get(0).getNameAsString();
        }
        if (e instanceof NameExpr n) {
            Optional<Type> local = declaredVariableType(n.getNameAsString(), searchScope, call);
            if (local.isPresent()) return typeHint(local.get(), typeParams);
            return declaredFieldType(n.getNameAsString(), chain).map(t -> typeHint(t, typeParams)).orElse("");
        }
        if (e instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr te && te.getTypeName().isEmpty()) {
            return declaredFieldType(fa.getNameAsString(), chain).map(t -> typeHint(t, typeParams)).orElse("");
        }
        if (e instanceof UnaryExpr u) {
            String inner = argumentType(u.getExpression(), call, searchScope, chain, typeParams);
            return switch (u.getOperator()) {
                case LOGICAL_COMPLEMENT -> "boolean";
                case MINUS, PLUS -> Set.of("int", "long", "float", "double").contains(inner) ? inner : "";
                default -> "";
            };
        }
        if (e instanceof BinaryExpr b) {
            return switch (b.getOperator()) {
                case EQUALS, NOT_EQUALS, LESS, GREATER, LESS_EQUALS, GREATER_EQUALS, AND, OR -> "boolean";
                case PLUS -> "String".equals(argumentType(b.getLeft(), call, searchScope, chain, typeParams))
                        || "String".equals(argumentType(b.getRight(), call, searchScope, chain, typeParams))
                        ? "String" : "";
                default -> "";
            };
        }
        return "";
    }

    /** A declared type as a hint: primitives by name, class types as written without generics, arrays as {@code T[]}. */
    private static String typeHint(Type t, Set<String> typeParams) {
        if (t instanceof PrimitiveType p) return p.asString();
        if (t instanceof ClassOrInterfaceType c) return typeHint(c.getNameWithScope(), typeParams);
        if (t instanceof ArrayType a) {
            String component = typeHint(a.getComponentType(), typeParams);
            return component.isEmpty() ? "" : component + "[]";
        }
        return ""; // var, lambda parameters without a type, wildcards, unions
    }

    private static String typeHint(String typeText, Set<String> typeParams) {
        String first = typeText.contains(".") ? typeText.substring(0, typeText.indexOf('.')) : typeText;
        return typeParams.contains(first) ? "" : typeText;
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static Set<String> withTypeParams(Set<String> outer, Node node) {
        if (!(node instanceof NodeWithTypeParameters<?> n) || n.getTypeParameters().isEmpty()) return outer;
        Set<String> all = new HashSet<>(outer);
        n.getTypeParameters().forEach(tp -> all.add(tp.getNameAsString()));
        return all;
    }

    private static String ownerSimple(String qn) {
        return qn.substring(qn.lastIndexOf('.') + 1);
    }

    private static String paramType(Parameter p) {
        return p.getType().asString() + (p.isVarArgs() ? "..." : "");
    }

    private static String typeSubtype(TypeDeclaration<?> t) {
        if (t instanceof EnumDeclaration) return "ENUM";
        if (t instanceof RecordDeclaration) return "RECORD";
        if (t instanceof AnnotationDeclaration) return "ANNOTATION";
        if (t instanceof ClassOrInterfaceDeclaration c) {
            return c.isInterface() ? "INTERFACE" : "CLASS";
        }
        return "CLASS";
    }

    private static String parentOf(String parentQn) {
        return parentQn == null ? "" : parentQn;
    }

    private static String modifiers(Node n) {
        if (n instanceof NodeWithModifiers<?> m) {
            return m.getModifiers().stream().map(mod -> mod.getKeyword().asString()).collect(Collectors.joining(" "));
        }
        return "";
    }

    private static int line(Node n) {
        return n.getRange().map(r -> r.begin.line).orElse(0);
    }

    private static int endLine(Node n) {
        return n.getRange().map(r -> r.end.line).orElse(0);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        String oneLine = s.replace('\r', ' ').replace('\n', ' ');
        return Texts.truncate(oneLine, MAX_MESSAGE);
    }
}
