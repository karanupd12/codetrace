package dev.codetrace.graph.engine;

import static dev.codetrace.graph.engine.GraphTestSupport.build;
import static dev.codetrace.graph.engine.GraphTestSupport.from;
import static org.assertj.core.api.Assertions.assertThat;

import dev.codetrace.graph.model.GraphModel.Edge;
import dev.codetrace.graph.model.GraphModel.EdgeType;
import dev.codetrace.graph.model.GraphModel.Resolution;
import dev.codetrace.graph.model.GraphModel.Result;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphTestLinksTest {

    private static List<String> targets(Result r, String testQn) {
        return from(r, testQn, EdgeType.TESTS).stream()
                .map(e -> r.nodes().stream().filter(n -> n.id().equals(e.dstId())).findFirst().orElseThrow().qualifiedName())
                .toList();
    }

    @Test
    void testClassesLinkToTheProductionClassTheyAreNamedAfter() {
        Result r = build(Map.of(
                "src/main/java/p/Order.java", "package p; public class Order {}",
                "src/main/java/p/Cart.java", "package p; public class Cart {}",
                "src/main/java/q/Price.java", "package q; public class Price {}",
                "src/test/java/p/OrderTest.java", "package p; class OrderTest {}",
                "src/test/java/p/TestCart.java", "package p; class TestCart {}",
                "src/test/java/p/PriceIT.java", "package p; import q.Price; class PriceIT {}"));

        assertThat(targets(r, "p.OrderTest")).containsExactly("p.Order");
        assertThat(targets(r, "p.TestCart")).containsExactly("p.Cart");
        assertThat(targets(r, "p.PriceIT")).containsExactly("q.Price");
        Edge link = from(r, "p.OrderTest", EdgeType.TESTS).get(0);
        assertThat(link.resolution()).isEqualTo(Resolution.RESOLVED);
        assertThat(link.evidenceKind()).isEqualTo("NAMING_CONVENTION");
        assertThat(link.confidence()).isEqualTo(GraphBuilder.NAMING_CONFIDENCE);
    }

    @Test
    void namesWithoutAProductionCounterpartLinkNothing() {
        Result r = build(Map.of(
                "src/main/java/p/Order.java", "package p; public class Order {}",
                "src/test/java/p/Fixtures.java", "package p; class Fixtures {}",
                "src/test/java/p/Helper.java", "package p; class Helper {}",
                "src/test/java/p/HelperTest.java", "package p; class HelperTest {}",
                "src/test/java/p/MissingTest.java", "package p; class MissingTest {}",
                "src/main/java/p/OrderTest.java", "package p; class OrderTest {}"));

        assertThat(r.edges()).noneMatch(e -> e.type() == EdgeType.TESTS);
    }

    @Test
    void testSubjectFollowsCommonNamingConventions() {
        assertThat(GraphBuilder.testSubject("OrderServiceTest")).isEqualTo("OrderService");
        assertThat(GraphBuilder.testSubject("OrderServiceTests")).isEqualTo("OrderService");
        assertThat(GraphBuilder.testSubject("OrderServiceIT")).isEqualTo("OrderService");
        assertThat(GraphBuilder.testSubject("OrderServiceITCase")).isEqualTo("OrderService");
        assertThat(GraphBuilder.testSubject("TestOrderService")).isEqualTo("OrderService");
        assertThat(GraphBuilder.testSubject("Test")).isNull();
        assertThat(GraphBuilder.testSubject("Testing")).isNull();
        assertThat(GraphBuilder.testSubject("ContestIT")).isEqualTo("Contest");
        assertThat(GraphBuilder.testSubject("Fixtures")).isNull();
    }
}
