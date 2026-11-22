package dev.codetrace.impact.engine;

import static dev.codetrace.impact.engine.EndpointImpactCalculator.symbol;
import static org.assertj.core.api.Assertions.assertThat;

import dev.codetrace.impact.model.ImpactAnalysis.AffectedSymbol;
import dev.codetrace.impact.model.ImpactAnalysis.EndpointImpact;
import dev.codetrace.impact.model.ImpactAnalysis.Hop;
import dev.codetrace.symbols.model.IndexedEndpoint;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EndpointImpactCalculatorTest {
    private static final String FILE = "web/src/main/java/shop/OrderController.java";
    private final EndpointImpactCalculator calculator = new EndpointImpactCalculator();

    private static IndexedEndpoint endpoint(String method, String route, String handler) {
        return new IndexedEndpoint(method, route, "shop.OrderController#" + handler, FILE, 10, "SPRING_MVC",
                route.isEmpty() ? "ROUTE_NOT_LITERAL" : "");
    }

    private static AffectedSymbol affected(String handler, int distance) {
        Hop hop = new Hop("shop.OrderController#" + handler, "shop.Order#price(double)", "CALLS", "RESOLVED", 1.0, FILE, 12);
        return new AffectedSymbol(UUID.randomUUID(), "METHOD", "shop.OrderController#" + handler, handler, FILE, 11, "web",
                false, distance, true, 1.0, "shop.Order#price(double)", List.of(hop));
    }

    private static List<String> lines(List<EndpointImpact> found) {
        return found.stream().map(e -> e.change() + " " + e.label()).toList();
    }

    @Test
    void classifiesEndpointsByHowTheChangeTouchesThem() {
        List<IndexedEndpoint> base = List.of(
                endpoint("GET", "/orders/{id}", "get(long)"),
                endpoint("DELETE", "/orders/{id}", "delete(long)"),
                endpoint("POST", "/orders", "create(Order)"),
                endpoint("GET", "/orders", "list()"),
                endpoint("GET", "/health", "health()"));
        List<IndexedEndpoint> head = List.of(
                endpoint("GET", "/orders/{id}", "get(long)"),
                endpoint("POST", "/orders", "create(Order,boolean)"),
                endpoint("GET", "/orders", "list()"),
                endpoint("GET", "/health", "health()"),
                endpoint("PUT", "/orders/{id}", "update(long,Order)"));

        List<EndpointImpact> found = calculator.calculate(base, head,
                Set.of(symbol(FILE, "shop.OrderController#create(Order)"), symbol(FILE, "shop.OrderController#delete(long)")),
                List.of(affected("list()", 2)));

        assertThat(lines(found)).containsExactly(
                "REMOVED DELETE /orders/{id}", "MODIFIED POST /orders", "AFFECTED GET /orders", "ADDED PUT /orders/{id}");
        EndpointImpact viaCall = found.get(2);
        assertThat(viaCall.handler()).isEqualTo("shop.OrderController#list()");
        assertThat(viaCall.distance()).isEqualTo(2);
        assertThat(viaCall.chain()).hasSize(1);
    }

    @Test
    void endpointsWithUnknownRoutesAreMatchedByTheirHandler() {
        List<IndexedEndpoint> base = List.of(endpoint("GET", "", "a()"), endpoint("GET", "", "b()"));
        List<IndexedEndpoint> head = List.of(endpoint("GET", "", "a()"));

        assertThat(lines(calculator.calculate(base, head, Set.of(), List.of())))
                .containsExactly("REMOVED GET (route not a literal)");
    }

    @Test
    void untouchedEndpointsAreNotReported() {
        List<IndexedEndpoint> both = List.of(endpoint("GET", "/health", "health()"));

        assertThat(calculator.calculate(both, both, Set.of(), List.of())).isEmpty();
    }
}
