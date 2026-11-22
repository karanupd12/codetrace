package dev.codetrace.graph.engine;

import static dev.codetrace.graph.engine.GraphTestSupport.build;
import static dev.codetrace.graph.engine.GraphTestSupport.from;
import static org.assertj.core.api.Assertions.assertThat;

import dev.codetrace.graph.model.GraphModel.EdgeType;
import dev.codetrace.graph.model.GraphModel.Resolution;
import dev.codetrace.graph.model.GraphModel.Result;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphOverrideLinksTest {

    private static List<String> overridden(Result r, String methodQn) {
        return from(r, methodQn, EdgeType.OVERRIDES).stream()
                .map(e -> r.nodes().stream().filter(n -> n.id().equals(e.dstId())).findFirst().orElseThrow().qualifiedName())
                .sorted().toList();
    }

    @Test
    void methodsLinkToEveryAncestorMethodTheyOverrideOrImplement() {
        Result r = build(Map.of(
                "p/Gateway.java", "package p; public interface Gateway { double charge(double amount); void close(); }",
                "p/Card.java", "package p; public class Card implements Gateway { public double charge(double a) { return a; }"
                        + " public void close() {} public double charge(int cents) { return cents; } }",
                "p/Gold.java", "package p; public class Gold extends Card { @Override public double charge(double a) { return a * 2; } }"));

        assertThat(overridden(r, "p.Card#charge(double)")).containsExactly("p.Gateway#charge(double)");
        assertThat(overridden(r, "p.Card#close()")).containsExactly("p.Gateway#close()");
        assertThat(overridden(r, "p.Card#charge(int)")).isEmpty(); // an overload, not an override
        assertThat(overridden(r, "p.Gold#charge(double)")).containsExactly("p.Card#charge(double)", "p.Gateway#charge(double)");
        assertThat(from(r, "p.Gold#charge(double)", EdgeType.OVERRIDES))
                .allSatisfy(e -> {
                    assertThat(e.resolution()).isEqualTo(Resolution.RESOLVED);
                    assertThat(e.evidenceKind()).isEqualTo("SIGNATURE_MATCH");
                });
    }

    @Test
    void staticPrivateAndConstructorMethodsNeverOverride() {
        Result r = build(Map.of(
                "p/Base.java", "package p; public class Base { static void s() {} private void hidden() {} Base() {} void open(java.util.List<String> xs) {} }",
                "p/Sub.java", "package p; public class Sub extends Base { static void s() {} private void hidden() {} Sub() {} void open(java.util.List<Integer> xs) {} }"));

        assertThat(overridden(r, "p.Sub#s()")).isEmpty();
        assertThat(overridden(r, "p.Sub#hidden()")).isEmpty();
        assertThat(r.edges()).noneMatch(e -> e.type() == EdgeType.OVERRIDES
                && r.nodes().stream().anyMatch(n -> n.id().equals(e.srcId()) && n.subtype().equals("CONSTRUCTOR")));
        // type arguments are ignored: the erased parameter types match
        assertThat(overridden(r, "p.Sub#open(java.util.List<Integer>)")).containsExactly("p.Base#open(java.util.List<String>)");
    }

    @Test
    void erasureDropsNestedTypeArguments() {
        assertThat(GraphBuilder.erased("put(Map<String,List<Integer>>,int)")).isEqualTo("put(Map,int)");
        assertThat(GraphBuilder.erased("go()")).isEqualTo("go()");
    }
}
