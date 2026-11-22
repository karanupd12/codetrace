package dev.codetrace.ingestion.parser;

import static org.assertj.core.api.Assertions.assertThat;

import dev.codetrace.ingestion.parser.JavaSymbolExtractor.Endpoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaEndpointExtractionTest {
    private final JavaSymbolExtractor extractor = new JavaSymbolExtractor();

    private List<String> endpoints(String source) {
        return extractor.extract(source).endpoints().stream()
                .map(e -> e.httpMethod() + " " + e.route() + " " + e.handlerQualifiedName().substring(e.handlerQualifiedName().indexOf('#') + 1)
                        + (e.reason().isEmpty() ? "" : " " + e.reason()))
                .toList();
    }

    @Test
    void springMappingsJoinTheTypeRouteWithEachMethodRoute() {
        List<String> found = endpoints("""
                package shop;
                import org.springframework.web.bind.annotation.*;

                @RestController
                @RequestMapping("/api/orders/")
                class OrderController {
                    @GetMapping("/{id}") Order get(@PathVariable long id) { return null; }
                    @PostMapping Order create(@RequestBody Order o) { return o; }
                    @RequestMapping(value = "/bulk", method = {RequestMethod.PUT, RequestMethod.PATCH}) void bulk() {}
                    @GetMapping({"/recent", "/latest"}) List<Order> recent() { return null; }
                    @RequestMapping("/any") void any() {}
                    @org.springframework.web.bind.annotation.DeleteMapping(path = "/{id}") void delete(long id) {}
                    @GetMapping("/v" + "2") void v2() {}
                    void helper() {}
                }
                """);

        assertThat(found).containsExactly(
                "GET /api/orders/{id} get(long)",
                "POST /api/orders create(Order)",
                "PUT /api/orders/bulk bulk()",
                "PATCH /api/orders/bulk bulk()",
                "GET /api/orders/recent recent()",
                "GET /api/orders/latest recent()",
                "ANY /api/orders/any any()",
                "DELETE /api/orders/{id} delete(long)",
                "GET /api/orders/v2 v2()");
    }

    @Test
    void routesThatAreNotLiteralsStayUnknown() {
        List<String> found = endpoints("""
                package shop;
                @RestController
                @RequestMapping(Paths.BASE)
                class A {
                    @GetMapping("/x") void x() {}
                }
                @RestController
                class B {
                    static final String P = "/p";
                    @GetMapping(P) void p() {}
                    @RequestMapping(value = "/m", method = METHOD) void m() {}
                }
                """);

        assertThat(found).containsExactly(
                "GET  x() ROUTE_NOT_LITERAL",
                "GET  p() ROUTE_NOT_LITERAL",
                "? /m m() METHOD_NOT_LITERAL");
    }

    @Test
    void jaxRsResourcesAreRecognised() {
        List<Endpoint> found = extractor.extract("""
                package users;
                import jakarta.ws.rs.*;

                @Path("/users")
                public class UserResource {
                    @GET @Path("{id}") public User find(@PathParam("id") String id) { return null; }
                    @POST public void add(User u) {}
                    public void notAnEndpoint() {}
                }
                """).endpoints();

        assertThat(found).extracting(e -> e.httpMethod() + " " + e.route() + " " + e.framework()).containsExactly(
                "GET /users/{id} JAX_RS", "POST /users JAX_RS");
        assertThat(found.get(0).handlerQualifiedName()).isEqualTo("users.UserResource#find(String)");
        assertThat(found.get(0).line()).isEqualTo(6);
    }

    @Test
    void joiningNormalisesSlashes() {
        assertThat(EndpointExtractor.join("", "")).isEqualTo("/");
        assertThat(EndpointExtractor.join("/a/", "/b/")).isEqualTo("/a/b");
        assertThat(EndpointExtractor.join("a", "b")).isEqualTo("/a/b");
    }
}
