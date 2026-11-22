package dev.codetrace.graph.service;

import dev.codetrace.common.web.ApiException;
import dev.codetrace.graph.model.Direction;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Parsing and defaults for graph query parameters supplied by API clients. */
final class GraphQueryParams {
    static final Set<String> DEFAULT_TYPES = Set.of("CALLS", "REFERENCES", "EXTENDS", "IMPLEMENTS", "IMPORTS", "TESTS", "OVERRIDES");
    static final Set<String> ALL_TYPES = Set.of("CONTAINS", "CALLS", "REFERENCES", "EXTENDS", "IMPLEMENTS", "IMPORTS", "TESTS",
            "OVERRIDES");
    static final Set<String> ALL_STATES = Set.of("RESOLVED", "AMBIGUOUS", "UNRESOLVED");
    static final Set<String> TRAVERSABLE_STATES = Set.of("RESOLVED", "AMBIGUOUS");
    static final Set<String> NODE_TYPES = Set.of("FILE", "PACKAGE", "TYPE", "METHOD", "FIELD");

    private GraphQueryParams() {
    }

    static Direction direction(String value, Direction fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Direction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("direction must be OUT, IN or BOTH.");
        }
    }

    /** A comma-separated set restricted to {@code allowed}; blank means {@code fallback}. */
    static Set<String> set(String csv, Set<String> fallback, Set<String> allowed, String name) {
        if (csv == null || csv.isBlank()) return fallback;
        Set<String> out = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String v = part.trim().toUpperCase(Locale.ROOT);
            if (v.isEmpty()) continue;
            if (!allowed.contains(v)) {
                throw ApiException.badRequest(name + " may contain only " + String.join(", ", new TreeSet<>(allowed)) + ".");
            }
            out.add(v);
        }
        return out.isEmpty() ? fallback : out;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
