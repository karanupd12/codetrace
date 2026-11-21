package dev.codetrace.graph.engine;

import static dev.codetrace.graph.engine.GraphTestSupport.build;
import static dev.codetrace.graph.engine.GraphTestSupport.dsts;
import static dev.codetrace.graph.engine.GraphTestSupport.from;
import static dev.codetrace.graph.engine.GraphTestSupport.only;
import static org.assertj.core.api.Assertions.assertThat;

import dev.codetrace.graph.model.GraphModel.Edge;
import dev.codetrace.graph.model.GraphModel.EdgeType;
import dev.codetrace.graph.model.GraphModel.Resolution;
import dev.codetrace.graph.model.GraphModel.Result;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Structure, imports and type-name resolution. */
class GraphTypeResolutionTest {

    // ---- structure ---------------------------------------------------------------------------------------------

    @Test
    void containmentFormsATree() {
        Result r = build(Map.of("src/p/A.java", "package p; class A { int f; void m() {} class In { void x() {} } }"));

        assertThat(dsts(r, "p", EdgeType.CONTAINS)).containsExactly("src/p/A.java");
        assertThat(dsts(r, "src/p/A.java", EdgeType.CONTAINS)).containsExactly("p.A");
        assertThat(dsts(r, "p.A", EdgeType.CONTAINS)).containsExactlyInAnyOrder("p.A#f", "p.A#m()", "p.A.In");
        assertThat(dsts(r, "p.A.In", EdgeType.CONTAINS)).containsExactly("p.A.In#x()");
        assertThat(from(r, "p.A", EdgeType.CONTAINS)).allSatisfy(e -> {
            assertThat(e.resolution()).isEqualTo(Resolution.RESOLVED);
            assertThat(e.evidenceKind()).isEqualTo("DECLARATION");
            assertThat(e.confidence()).isEqualTo(1.0);
        });
    }

    // ---- scoping order -----------------------------------------------------------------------------------------

    @Test
    void samePackageTypesResolveWithFullConfidence() {
        Result r = build(Map.of(
                "src/p/A.java", "package p; class A extends B implements I {}",
                "src/p/B.java", "package p; class B {}",
                "src/p/I.java", "package p; interface I {}"));

        Edge ext = only(r, "p.A", EdgeType.EXTENDS);
        assertThat(GraphTestSupport.dst(r, ext)).isEqualTo("p.B");
        assertThat(ext.resolution()).isEqualTo(Resolution.RESOLVED);
        assertThat(ext.evidenceKind()).isEqualTo("SAME_PACKAGE");
        assertThat(ext.confidence()).isEqualTo(1.0);
        assertThat(dsts(r, "p.A", EdgeType.IMPLEMENTS)).containsExactly("p.I");
    }

    @Test
    void singleTypeImportShadowsTheSamePackage() {
        Result r = build(Map.of(
                "src/p/B.java", "package p; class B {}",
                "src/q/B.java", "package q; class B {}",
                "src/q/C.java", "package q; import p.B; class C extends B {}"));

        Edge e = only(r, "q.C", EdgeType.EXTENDS);
        assertThat(GraphTestSupport.dst(r, e)).isEqualTo("p.B");
        assertThat(e.evidenceKind()).isEqualTo("IMPORT");
        assertThat(dsts(r, "src/q/C.java", EdgeType.IMPORTS)).containsExactly("p.B");
    }

    @Test
    void wildcardImportResolvesUniqueCandidatesWithReducedConfidence() {
        Result r = build(Map.of(
                "src/a/Util.java", "package a; public class Util {}",
                "src/c/C.java", "package c; import a.*; class C { Util u; }"));

        Edge e = only(r, "c.C#u", EdgeType.REFERENCES);
        assertThat(GraphTestSupport.dst(r, e)).isEqualTo("a.Util");
        assertThat(e.evidenceKind()).isEqualTo("WILDCARD_IMPORT");
        assertThat(e.confidence()).isEqualTo(0.9);
        assertThat(dsts(r, "src/c/C.java", EdgeType.IMPORTS)).containsExactly("a");
    }

    @Test
    void competingWildcardImportsAreAmbiguousNotGuessed() {
        Result r = build(Map.of(
                "src/a/Util.java", "package a; public class Util {}",
                "src/b/Util.java", "package b; public class Util {}",
                "src/c/C.java", "package c; import a.*; import b.*; class C { Util u; }"));

        List<Edge> edges = from(r, "c.C#u", EdgeType.REFERENCES);
        assertThat(edges).hasSize(2).allSatisfy(e -> {
            assertThat(e.resolution()).isEqualTo(Resolution.AMBIGUOUS);
            assertThat(e.reason()).isEqualTo("MULTIPLE_DECLARATIONS");
            assertThat(e.confidence()).isCloseTo(0.45, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(e.dstId()).isNotNull();
        });
        assertThat(edges.stream().map(Edge::alternativeGroup).distinct()).hasSize(1).doesNotContain("");
        assertThat(dsts(r, "c.C#u", EdgeType.REFERENCES)).containsExactlyInAnyOrder("a.Util", "b.Util");
    }

    @Test
    void typesOutsideTheRepositoryAreUnresolvedWithAnExplicitReason() {
        Result r = build(Map.of("src/p/A.java", "package p; import java.util.List; class A { List<String> l; Missing m; }"));

        for (String src : List.of("p.A#l")) {
            List<Edge> edges = from(r, src, EdgeType.REFERENCES);
            assertThat(edges).extracting(Edge::dstName).containsExactlyInAnyOrder("java.util.List", "String");
            assertThat(edges).allSatisfy(e -> {
                assertThat(e.resolution()).isEqualTo(Resolution.UNRESOLVED);
                assertThat(e.dstId()).isNull();
                assertThat(e.reason()).isEqualTo("NOT_DECLARED_IN_REPOSITORY");
                assertThat(e.confidence()).isZero();
            });
        }
        assertThat(only(r, "p.A#m", EdgeType.REFERENCES).dstName()).isEqualTo("Missing");
        assertThat(only(r, "src/p/A.java", EdgeType.IMPORTS).dstName()).isEqualTo("java.util.List");
    }

    // ---- nested types ------------------------------------------------------------------------------------------

    @Test
    void nestedTypesResolveByScopeAndByQualifiedPath() {
        Result r = build(Map.of(
                "src/p/Order.java", "package p; class Order { class Line { class Detail {} } Line first; }",
                "src/p/X.java", "package p; class X { Order.Line l; Order.Line.Detail d; Order.Missing q; }"));

        Edge scoped = only(r, "p.Order#first", EdgeType.REFERENCES);
        assertThat(GraphTestSupport.dst(r, scoped)).isEqualTo("p.Order.Line");
        assertThat(scoped.evidenceKind()).isEqualTo("ENCLOSING_TYPE");
        assertThat(dsts(r, "p.X#l", EdgeType.REFERENCES)).containsExactly("p.Order.Line");
        assertThat(dsts(r, "p.X#d", EdgeType.REFERENCES)).containsExactly("p.Order.Line.Detail");
        Edge missing = only(r, "p.X#q", EdgeType.REFERENCES);
        assertThat(missing.resolution()).isEqualTo(Resolution.UNRESOLVED);
        assertThat(missing.reason()).isEqualTo("NESTED_TYPE_NOT_FOUND");
    }

    @Test
    void typesDoNotReferenceThemselves() {
        Result r = build(Map.of("src/p/A.java", "package p; class A implements Comparable<A> { A next; }"));

        assertThat(dsts(r, "p.A", EdgeType.REFERENCES)).doesNotContain("p.A");
        assertThat(dsts(r, "p.A#next", EdgeType.REFERENCES)).containsExactly("p.A"); // a field referencing its type is real
    }

    // ---- duplicate declarations across modules -------------------------------------------------------------------

    @Test
    void sameModuleDeclarationWinsWhenTwoModulesDeclareTheSameName() {
        Result r = build(Map.of(
                "a/src/main/java/p/X.java", "package p; class X {}",
                "b/src/main/java/p/X.java", "package p; class X {}",
                "a/src/main/java/p/Y.java", "package p; class Y { X x; }"));

        Edge e = only(r, "p.Y#x", EdgeType.REFERENCES);
        assertThat(e.resolution()).isEqualTo(Resolution.RESOLVED);
        assertThat(e.evidenceKind()).isEqualTo("SAME_PACKAGE+SAME_MODULE");
        assertThat(e.confidence()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-9));
        UUIDCheck.pointsInto(r, e, "a/src/main/java/p/X.java");
    }

    @Test
    void referencesFromAThirdModuleStayAmbiguous() {
        Result r = build(Map.of(
                "a/src/main/java/p/X.java", "package p; class X {}",
                "b/src/main/java/p/X.java", "package p; class X {}",
                "z/src/main/java/p/Z.java", "package p; class Z { X x; }"));

        List<Edge> edges = from(r, "p.Z#x", EdgeType.REFERENCES);
        assertThat(edges).hasSize(2).allSatisfy(e -> assertThat(e.resolution()).isEqualTo(Resolution.AMBIGUOUS));
    }

    // ---- imports -----------------------------------------------------------------------------------------------

    @Test
    void staticAndWildcardImportsPointAtTheirTypeOrPackage() {
        Result r = build(Map.of(
                "src/p/Util.java", "package p; public class Util { public static void help() {} }",
                "src/q/A.java", "package q; import static p.Util.help; import p.*; class A {}",
                "src/r/B.java", "package r; import static p.Util.*; class B {}"));

        assertThat(from(r, "src/q/A.java", EdgeType.IMPORTS)).extracting(e -> GraphTestSupport.dst(r, e))
                .containsExactlyInAnyOrder("p.Util", "p");
        assertThat(only(r, "src/r/B.java", EdgeType.IMPORTS).evidenceKind()).isEqualTo("STATIC_IMPORT");
        assertThat(dsts(r, "src/r/B.java", EdgeType.IMPORTS)).containsExactly("p.Util");
    }

    /** Small helper: asserts which file an edge's target lives in. */
    private static final class UUIDCheck {
        static void pointsInto(Result r, Edge e, String path) {
            assertThat(r.nodes().stream().filter(n -> n.id().equals(e.dstId())).findFirst().orElseThrow().path())
                    .isEqualTo(path);
        }
    }
}
