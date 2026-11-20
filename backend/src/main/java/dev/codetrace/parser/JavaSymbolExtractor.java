package dev.codetrace.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Pure syntactic extraction of Java declarations. Parses text only: it never compiles, loads, or executes code.
 *
 * <p>Identity: types are {@code pkg.Outer.Inner}; members are {@code pkg.Outer.Inner#name(ParamTypes)} where
 * parameter types are the textual types as written (generics kept, varargs as {@code ...}). Resolving those names to
 * other declarations is a later milestone; nothing here claims a resolved relationship.
 */
public final class JavaSymbolExtractor {
    public static final String PARSER_VERSION = "javaparser-core-3.26.4";
    public static final String EXTRACTION_METHOD = "java-declarations-v1";
    private static final int MAX_MESSAGE = 300;

    public enum FileStatus { PARSED, PARTIAL, FAILED }

    public record Symbol(String kind, String subtype, String simpleName, String qualifiedName, String signature,
                         String parentQualifiedName, String modifiers, int startLine, int endLine) {
    }

    public record Import(String name, boolean isStatic, boolean wildcard, int line) {
    }

    public record Diagnostic(String severity, String code, String message, int line) {
    }

    public record FileExtraction(FileStatus status, String packageName, List<Import> imports, List<Symbol> symbols,
                                 List<Diagnostic> diagnostics) {
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
            return new FileExtraction(FileStatus.FAILED, "", List.of(), List.of(),
                    List.of(new Diagnostic("ERROR", "PARSER_CRASH", e.getClass().getSimpleName(), 0)));
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Problem p : result.getProblems()) {
            int line = p.getLocation().flatMap(l -> l.getBegin().getRange()).map(r -> r.begin.line).orElse(0);
            diagnostics.add(new Diagnostic("ERROR", "SYNTAX_ERROR", truncate(p.getMessage()), line));
        }
        if (result.getResult().isEmpty()) {
            return new FileExtraction(FileStatus.FAILED, "", List.of(), List.of(), diagnostics);
        }

        CompilationUnit cu = result.getResult().get();
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        List<Import> imports = new ArrayList<>();
        for (ImportDeclaration i : cu.getImports()) {
            String name = i.getNameAsString() + (i.isAsterisk() ? ".*" : "");
            imports.add(new Import(name, i.isStatic(), i.isAsterisk(), line(i)));
        }
        List<Symbol> symbols = new ArrayList<>();
        try {
            for (TypeDeclaration<?> t : cu.getTypes()) {
                visitType(t, pkg.isEmpty() ? null : pkg, symbols);
            }
        } catch (RuntimeException | StackOverflowError e) {
            diagnostics.add(new Diagnostic("ERROR", "EXTRACTION_ERROR", e.getClass().getSimpleName(), 0));
            return new FileExtraction(FileStatus.PARTIAL, pkg, imports, symbols, diagnostics);
        }
        FileStatus status = diagnostics.isEmpty() ? FileStatus.PARSED : FileStatus.PARTIAL;
        return new FileExtraction(status, pkg, imports, symbols, diagnostics);
    }

    private void visitType(TypeDeclaration<?> type, String parentQn, List<Symbol> out) {
        String qn = parentQn == null ? type.getNameAsString() : parentQn + "." + type.getNameAsString();
        out.add(new Symbol("TYPE", typeSubtype(type), type.getNameAsString(), qn, "", parentOf(parentQn),
                modifiers(type), line(type), endLine(type)));

        if (type instanceof EnumDeclaration e) {
            for (EnumConstantDeclaration c : e.getEntries()) {
                out.add(new Symbol("FIELD", "ENUM_CONSTANT", c.getNameAsString(), qn + "#" + c.getNameAsString(), "",
                        qn, "", line(c), endLine(c)));
            }
        }
        List<String> recordParamTypes = List.of();
        if (type instanceof RecordDeclaration r) {
            recordParamTypes = r.getParameters().stream().map(JavaSymbolExtractor::paramType).toList();
            for (Parameter p : r.getParameters()) {
                out.add(new Symbol("FIELD", "RECORD_COMPONENT", p.getNameAsString(),
                        qn + "#" + p.getNameAsString(), "", qn, "", line(p), endLine(p)));
            }
        }

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                visitType(nested, qn, out);
            } else if (member instanceof MethodDeclaration m) {
                out.add(callable(m, m.getNameAsString(), qn));
            } else if (member instanceof ConstructorDeclaration c) {
                out.add(callable(c, "<init>", qn));
            } else if (member instanceof CompactConstructorDeclaration c) {
                String sig = "<init>(" + String.join(",", recordParamTypes) + ")";
                out.add(new Symbol("METHOD", "CONSTRUCTOR", type.getNameAsString(), qn + "#" + sig, sig, qn,
                        modifiers(c), line(c), endLine(c)));
            } else if (member instanceof FieldDeclaration f) {
                for (VariableDeclarator v : f.getVariables()) {
                    out.add(new Symbol("FIELD", "FIELD", v.getNameAsString(), qn + "#" + v.getNameAsString(), "", qn,
                            modifiers(f), line(f), endLine(f)));
                }
            } else if (member instanceof AnnotationMemberDeclaration a) {
                String sig = a.getNameAsString() + "()";
                out.add(new Symbol("METHOD", "ANNOTATION_MEMBER", a.getNameAsString(), qn + "#" + sig, sig, qn,
                        modifiers(a), line(a), endLine(a)));
            }
            // Initializer blocks, local and anonymous classes are not addressable declarations; skipped by design.
        }
    }

    private static Symbol callable(CallableDeclaration<?> c, String name, String ownerQn) {
        String params = c.getParameters().stream().map(JavaSymbolExtractor::paramType).collect(Collectors.joining(","));
        String sig = name + "(" + params + ")";
        String subtype = c instanceof ConstructorDeclaration ? "CONSTRUCTOR" : "METHOD";
        return new Symbol("METHOD", subtype, c instanceof ConstructorDeclaration ? ownerSimple(ownerQn) : name,
                ownerQn + "#" + sig, sig, ownerQn, modifiers(c), line(c), endLine(c));
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
        if (t instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration c) {
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
        return oneLine.length() <= MAX_MESSAGE ? oneLine : oneLine.substring(0, MAX_MESSAGE);
    }
}
