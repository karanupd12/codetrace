package dev.codetrace.symbols.model;

/**
 * An HTTP endpoint an index job found in the source. {@code route} is empty and {@code reason} says why when the route
 * is not written as a literal; {@code httpMethod} is {@code ANY} for a mapping that accepts every method and {@code ?}
 * when it is not a literal.
 */
public record IndexedEndpoint(String httpMethod, String route, String handlerQualifiedName, String path, int line,
                              String framework, String reason) {
    /** {@code GET /orders/{id}}, or the method with a marker when the route is unknown. */
    public String label() {
        return httpMethod + " " + (route.isEmpty() ? "(route not a literal)" : route);
    }
}
