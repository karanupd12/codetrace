package dev.codetrace.graph.engine;

import static dev.codetrace.graph.engine.GraphTestSupport.build;
import static dev.codetrace.graph.engine.GraphTestSupport.dsts;
import static dev.codetrace.graph.engine.GraphTestSupport.from;
import static dev.codetrace.graph.engine.GraphTestSupport.only;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import dev.codetrace.graph.model.GraphModel.Edge;
import dev.codetrace.graph.model.GraphModel.EdgeType;
import dev.codetrace.graph.model.GraphModel.Resolution;
import dev.codetrace.graph.model.GraphModel.Result;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Call-site resolution: by receiver, name and argument count. Never guesses past the evidence. */
class GraphCallResolutionTest {

    private static Result one(String body) {
        return build(Map.of("src/p/A.java", "package p;\n" + body));
    }

    private static Edge call(Result r, String owner) {
        return only(r, owner, EdgeType.CALLS);
    }

    // ---- unqualified calls ----------------------------------------------------------------------------------------

    @Test
    void aUniquelyNamedMethodResolvesWithFullConfidence() {
        Result r = one("class A { void go() { helper(); } void helper() {} }");

        Edge e = call(r, "p.A#go()");
        assertThat(GraphTestSupport.dst(r, e)).isEqualTo("p.A#helper()");
        assertThat(e.resolution()).isEqualTo(Resolution.RESOLVED);
        assertThat(e.confidence()).isEqualTo(1.0);
        assertThat(e.evidenceKind()).isEqualTo("ENCLOSING_SCOPE/UNIQUE_NAME");
        assertThat(e.evidenceLine()).isEqualTo(2);
        assertThat(e.evidencePath()).isEqualTo("src/p/A.java");
    }

    @Test
    void overloadsAreSeparatedByArityWithReducedConfidence() {
        Result r = one("class A { void go() { add(1); add(1, 2); } void add(int a) {} void add(int a, int b) {} }");

        assertThat(dsts(r, "p.A#go()", EdgeType.CALLS)).containsExactlyInAnyOrder("p.A#add(int)", "p.A#add(int,int)");
        assertThat(from(r, "p.A#go()", EdgeType.CALLS)).allSatisfy(e -> {
            assertThat(e.resolution()).isEqualTo(Resolution.RESOLVED);
            assertThat(e.confidence()).isEqualTo(0.9);                 // argument types were not checked
            assertThat(e.evidenceKind()).endsWith("UNIQUE_NAME_AND_ARITY");
        });
    }

    @Test
    void equalArityOverloadsStayAmbiguous() {
        Result r = one("class A { void go(Object x) { add(x); } void add(String s) {} void add(int n) {} }");

        List<Edge> edges = from(r, "p.A#go(Object)", EdgeType.CALLS);
        assertThat(edges).hasSize(2).allSatisfy(e -> {
            assertThat(e.resolution()).isEqualTo(Resolution.AMBIGUOUS);
            assertThat(e.reason()).isEqualTo("MULTIPLE_CANDIDATES");
            assertThat(e.confidence()).isCloseTo(0.45, offset(1e-9));
        });
        assertThat(edges.stream().map(Edge::alternativeGroup).distinct()).hasSize(1);
        assertThat(dsts(r, "p.A#go(Object)", EdgeType.CALLS)).containsExactlyInAnyOrder("p.A#add(String)", "p.A#add(int)");
    }

    @Test
    void varargsAcceptAnyTrailingArgumentCount() {
        Result r = one("class A { void go() { log(\"a\"); log(\"a\", 1, 2); log(); } void log(String f, Object... args) {} }");

        assertThat(dsts(r, "p.A#go()", EdgeType.CALLS)).containsExactlyInAnyOrder(
                "p.A#log(String,Object...)", "?p.A#log/0");
        Edge merged = from(r, "p.A#go()", EdgeType.CALLS).stream()
                .filter(e -> e.resolution() == Resolution.RESOLVED).findFirst().orElseThrow();
        assertThat(merged.occurrences()).isEqualTo(2);
        Edge bad = from(r, "p.A#go()", EdgeType.CALLS).stream()
                .filter(e -> e.resolution() == Resolution.UNRESOLVED).findFirst().orElseThrow();
        assertThat(bad.reason()).isEqualTo("ARITY_MISMATCH");
    }

    @Test
    void methodsInOuterClassesAreVisibleFromNestedOnes() {
        Result r = one("class Outer { void helper() {} class In { void go() { helper(); } } }");

        assertThat(dsts(r, "p.Outer.In#go()", EdgeType.CALLS)).containsExactly("p.Outer#helper()");
    }

    @Test
    void anOverrideHidesTheMethodItOverrides() {
        Result r = one("class B { void m() {} } class A extends B { void m() {} void go() { m(); } }");

        assertThat(dsts(r, "p.A#go()", EdgeType.CALLS)).containsExactly("p.A#m()");
    }

    // ---- inheritance ------------------------------------------------------------------------------------------------

    @Test
    void inheritedMethodsResolveThroughResolvedSupertypes() {
        Result r = one("""
                class B { void base() {} }
                interface I { default void d() {} }
                class A extends B implements I {
                    void go() { base(); this.base(); super.base(); d(); }
                }""");

        List<Edge> calls = from(r, "p.A#go()", EdgeType.CALLS);
        assertThat(dsts(r, "p.A#go()", EdgeType.CALLS)).containsExactlyInAnyOrder("p.B#base()", "p.I#d()");
        Edge base = calls.stream().filter(e -> GraphTestSupport.dst(r, e).equals("p.B#base()")).findFirst().orElseThrow();
        assertThat(base.occurrences()).isEqualTo(3); // three call sites merged into one edge
    }

    @Test
    void aMethodMayComeFromASupertypeOutsideTheRepository() {
        Result r = one("class A extends ext.Base { void go() { inherited(); } }");

        Edge e = call(r, "p.A#go()");
        assertThat(e.resolution()).isEqualTo(Resolution.UNRESOLVED);
        assertThat(e.reason()).isEqualTo("EXTERNAL_SUPERTYPE");
        assertThat(e.dstName()).isEqualTo("p.A#inherited/0");
    }

    @Test
    void trulyMissingMethodsAreNotFoundAndWrongArityIsReported() {
        Result r = one("class A { void go() { missing(); f(); } void f(int a) {} }");

        List<Edge> edges = from(r, "p.A#go()", EdgeType.CALLS);
        assertThat(edges).extracting(Edge::reason).containsExactlyInAnyOrder("METHOD_NOT_FOUND", "ARITY_MISMATCH");
    }

    @Test
    void cyclicInheritanceDoesNotHangTheResolver() {
        Result r = one("class A extends B { void go() { nothing(); } } class B extends A {}");

        assertThat(call(r, "p.A#go()").resolution()).isEqualTo(Resolution.UNRESOLVED);
    }

    // ---- qualified calls --------------------------------------------------------------------------------------------

    @Test
    void receiverTypesFromParametersLocalsFieldsAndStaticNamesResolve() {
        Result r = build(Map.of(
                "src/p/Order.java", "package p; class Order { void total() {} static Order of() { return null; } }",
                "src/p/Util.java", "package p; class Util { static void help() {} }",
                "src/q/Svc.java", """
                        package q;
                        import p.Order;
                        import p.Util;
                        class Svc {
                            Order field;
                            void run(Order param) {
                                Order local = Order.of();
                                param.total(); local.total(); field.total(); this.field.total();
                                Util.help();
                            }
                        }"""));

        List<Edge> edges = from(r, "q.Svc#run(Order)", EdgeType.CALLS);
        assertThat(dsts(r, "q.Svc#run(Order)", EdgeType.CALLS))
                .containsExactlyInAnyOrder("p.Order#total()", "p.Order#of()", "p.Util#help()");
        Edge total = edges.stream().filter(e -> GraphTestSupport.dst(r, e).equals("p.Order#total()")).findFirst().orElseThrow();
        assertThat(total.occurrences()).isEqualTo(4);
        assertThat(total.evidenceKind()).startsWith("RECEIVER_IMPORT/");
    }

    @Test
    void receiversOutsideTheRepositoryAreUnresolvedAndNamed() {
        Result r = one("class A { java.util.List<String> xs; void go() { xs.add(\"a\"); } }");

        Edge e = call(r, "p.A#go()");
        assertThat(e.resolution()).isEqualTo(Resolution.UNRESOLVED);
        assertThat(e.reason()).isEqualTo("RECEIVER_TYPE_NOT_IN_REPOSITORY");
        assertThat(e.dstName()).isEqualTo("java.util.List#add/1");
    }

    @Test
    void unknowableReceiversAreNeverGuessedFromTheMethodName() {
        // 'go' exists on exactly one repository type, but nothing proves the receiver is that type.
        Result r = build(Map.of(
                "src/p/Target.java", "package p; class Target { void go() {} }",
                "src/p/A.java", "package p; class A { Target make() { return null; } void run() { make().go(); var v = make(); v.go(); } }"));

        List<Edge> edges = from(r, "p.A#run()", EdgeType.CALLS).stream()
                .filter(e -> e.dstName() != null && e.dstName().contains("go")).toList();
        assertThat(edges).isNotEmpty().allSatisfy(e -> {
            assertThat(e.resolution()).isEqualTo(Resolution.UNRESOLVED);
            assertThat(e.reason()).isEqualTo("RECEIVER_TYPE_UNKNOWN");
            assertThat(e.dstName()).isEqualTo("?#go/0");
        });
    }

    @Test
    void ambiguousReceiverTypesMakeTheCallUnresolved() {
        Result r = build(Map.of(
                "a/src/main/java/p/X.java", "package p; class X { void m() {} }",
                "b/src/main/java/p/X.java", "package p; class X { void m() {} }",
                "z/src/main/java/p/Z.java", "package p; class Z { void go(X x) { x.m(); } }"));

        Edge e = call(r, "p.Z#go(X)");
        assertThat(e.resolution()).isEqualTo(Resolution.UNRESOLVED);
        assertThat(e.reason()).isEqualTo("RECEIVER_TYPE_AMBIGUOUS");
    }

    @Test
    void staticImportsProvideUnqualifiedMethods() {
        Result r = build(Map.of(
                "src/p/Util.java", "package p; public class Util { public static void help() {} public static void other() {} }",
                "src/q/A.java", "package q; import static p.Util.help; import static p.Util.*; class A { void go() { help(); other(); } }"));

        assertThat(dsts(r, "q.A#go()", EdgeType.CALLS)).containsExactlyInAnyOrder("p.Util#help()", "p.Util#other()");
        assertThat(from(r, "q.A#go()", EdgeType.CALLS)).allSatisfy(e -> assertThat(e.evidenceKind()).startsWith("STATIC_IMPORT/"));
    }

    // ---- constructors -----------------------------------------------------------------------------------------------

    @Test
    void objectCreationResolvesToTheMatchingConstructor() {
        Result r = one("class Order { Order(int n) {} } class Svc { void a() { new Order(1); new Order(1, 2); } }");

        List<Edge> calls = from(r, "p.Svc#a()", EdgeType.CALLS);
        assertThat(calls).hasSize(2);
        Edge ok = calls.stream().filter(e -> e.resolution() == Resolution.RESOLVED).findFirst().orElseThrow();
        assertThat(GraphTestSupport.dst(r, ok)).isEqualTo("p.Order#<init>(int)");
        Edge bad = calls.stream().filter(e -> e.resolution() == Resolution.UNRESOLVED).findFirst().orElseThrow();
        assertThat(bad.reason()).isEqualTo("ARITY_MISMATCH");
        assertThat(dsts(r, "p.Svc#a()", EdgeType.REFERENCES)).contains("p.Order"); // the type usage is its own edge
    }

    @Test
    void implicitConstructorsProduceNoCallEdge() {
        Result r = one("""
                class P {}
                record R(int a, int b) {}
                class Svc { void a() { new P(); new R(1, 2); } void b() { new R(1); } }""");

        assertThat(from(r, "p.Svc#a()", EdgeType.CALLS)).isEmpty();             // default and canonical record constructors
        assertThat(call(r, "p.Svc#b()").resolution()).isEqualTo(Resolution.UNRESOLVED); // no 1-arg record constructor
    }

    @Test
    void thisAndSuperConstructorCallsResolveWithinTheHierarchy() {
        Result r = one("""
                class B { B(int n) {} }
                class A extends B {
                    A() { this(1); }
                    A(int n) { super(n); }
                }
                class C extends ext.Base { C() { super(); } }
                class D { D() { super(); } }""");

        assertThat(dsts(r, "p.A#<init>()", EdgeType.CALLS)).containsExactly("p.A#<init>(int)");
        assertThat(dsts(r, "p.A#<init>(int)", EdgeType.CALLS)).containsExactly("p.B#<init>(int)");
        assertThat(call(r, "p.C#<init>()").reason()).isEqualTo("SUPERTYPE_NOT_IN_REPOSITORY");
        assertThat(from(r, "p.D#<init>()", EdgeType.CALLS)).isEmpty(); // implicit Object()
    }

    // ---- overloads told apart by argument types ------------------------------------------------------------------------

    @Test
    void argumentTypesPickTheOnlyApplicableOverload() {
        Result r = one("""
                class A {
                    void go(String s) { put(s); put(1); put(true); }
                    void put(String s) {} void put(int n) {} void put(Boolean b) {}
                }""");

        assertThat(dsts(r, "p.A#go(String)", EdgeType.CALLS))
                .containsExactlyInAnyOrder("p.A#put(String)", "p.A#put(int)", "p.A#put(Boolean)");
        assertThat(from(r, "p.A#go(String)", EdgeType.CALLS)).allSatisfy(e -> {
            assertThat(e.resolution()).isEqualTo(Resolution.RESOLVED);
            assertThat(e.confidence()).isEqualTo(0.9);
            assertThat(e.evidenceKind()).endsWith("ARGUMENT_TYPES");
        });
    }

    @Test
    void aSubtypeArgumentSelectsTheMatchingParameterType() {
        Result r = one("""
                class Shape {}
                class Circle extends Shape {}
                class Label {}
                class A { void go() { draw(new Circle()); } void draw(Shape s) {} void draw(Label l) {} }""");

        assertThat(dsts(r, "p.A#go()", EdgeType.CALLS)).filteredOn(d -> d.contains("draw")).containsExactly("p.A#draw(Shape)");
    }

    @Test
    void primitivesBeatBoxingAndTheMoreSpecificOverloadWins() {
        Result r = one("""
                class A {
                    void go() { f(5); g("x"); }
                    void f(int a) {} void f(Object o) {}
                    void g(Object o) {} void g(String s) {}
                }""");

        assertThat(dsts(r, "p.A#go()", EdgeType.CALLS)).containsExactlyInAnyOrder("p.A#f(int)", "p.A#g(String)");
    }

    @Test
    void nullFitsAnyReferenceParameterButNotAPrimitive() {
        Result r = one("class A { void go() { take(null); } void take(int n) {} void take(String s) {} }");

        assertThat(dsts(r, "p.A#go()", EdgeType.CALLS)).containsExactly("p.A#take(String)");
    }

    @Test
    void variableArityIsConsideredOnlyAfterFixedArityOverloads() {
        Result r = one("class A { void go() { show(\"a\"); show(\"a\", \"b\"); } void show(String s) {} void show(String... all) {} }");

        assertThat(dsts(r, "p.A#go()", EdgeType.CALLS))
                .containsExactlyInAnyOrder("p.A#show(String)", "p.A#show(String...)");
        assertThat(from(r, "p.A#go()", EdgeType.CALLS)).filteredOn(e -> GraphTestSupport.dst(r, e).equals("p.A#show(String)"))
                .allSatisfy(e -> assertThat(e.evidenceKind()).endsWith("ARGUMENT_TYPES"));
    }

    @Test
    void constructorOverloadsAreNarrowedByArgumentTypes() {
        Result r = one("class B { B(String s) {} B(int n) {} } class A { void go() { new B(1); } }");

        Edge e = call(r, "p.A#go()");
        assertThat(GraphTestSupport.dst(r, e)).isEqualTo("p.B#<init>(int)");
        assertThat(e.resolution()).isEqualTo(Resolution.RESOLVED);
    }

    @Test
    void anArgumentWithoutATypeHintKeepsEveryOverload() {
        Result r = one("class A { void go() { take(make()); } Object make() { return null; } void take(String s) {} void take(int n) {} }");

        assertThat(from(r, "p.A#go()", EdgeType.CALLS)).filteredOn(e -> e.resolution() == Resolution.AMBIGUOUS).hasSize(2);
    }

    @Test
    void typesOutsideTheRepositoryNeverRuleOutAnOverload() {
        Result r = one("""
                class A {
                    void go(java.util.List<String> xs) { take(xs); }
                    void take(Iterable<String> a) {} void take(Collection<String> b) {}
                }""");

        assertThat(from(r, "p.A#go(java.util.List<String>)", EdgeType.CALLS))
                .filteredOn(e -> e.resolution() == Resolution.AMBIGUOUS).hasSize(2);
    }

    @Test
    void overlappingCandidateSetsOfOneCallerShareOneAlternativeGroup() {
        Result r = one("""
                interface X {} interface Y {} interface Z {}
                class XY implements X, Y {}
                class YZ implements Y, Z {}
                class A {
                    void go() {
                        take(new XY());
                        take(new YZ());
                    }
                    void take(X x) {} void take(Y y) {} void take(Z z) {}
                }""");

        List<Edge> ambiguous = from(r, "p.A#go()", EdgeType.CALLS).stream()
                .filter(e -> e.resolution() == Resolution.AMBIGUOUS).toList();
        assertThat(ambiguous).hasSize(3);
        assertThat(ambiguous.stream().map(Edge::alternativeGroup).distinct()).hasSize(1);
    }

    // ---- type hierarchy bookkeeping ------------------------------------------------------------------------------------

    @Test
    void hierarchyEdgesRecordExternalSupertypesAsUnresolved() {
        Result r = one("class A extends ext.Base implements java.io.Serializable {}");

        assertThat(from(r, "p.A", EdgeType.EXTENDS)).extracting(Edge::resolution).containsExactly(Resolution.UNRESOLVED);
        assertThat(only(r, "p.A", EdgeType.IMPLEMENTS).dstName()).isEqualTo("java.io.Serializable");
    }
}
