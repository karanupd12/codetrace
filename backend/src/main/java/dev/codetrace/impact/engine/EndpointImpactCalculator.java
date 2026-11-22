package dev.codetrace.impact.engine;

import dev.codetrace.impact.model.ImpactAnalysis.AffectedSymbol;
import dev.codetrace.impact.model.ImpactAnalysis.EndpointImpact;
import dev.codetrace.symbols.model.IndexedEndpoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which HTTP endpoints a change touches, from the endpoints of the base and head revisions. Endpoints are matched by
 * HTTP method and route; one whose route is not a literal is matched by its handler instead. An endpoint only in the
 * head is ADDED, only in the base REMOVED; one in both is MODIFIED when its handler changed and AFFECTED when its handler
 * depends on the change (with that handler's evidence chain).
 */
public class EndpointImpactCalculator {
    public static final String ADDED = "ADDED";
    public static final String REMOVED = "REMOVED";
    public static final String MODIFIED = "MODIFIED";
    public static final String AFFECTED = "AFFECTED";
    private static final List<String> ORDER = List.of(REMOVED, MODIFIED, AFFECTED, ADDED);

    /**
     * @param changedHandlers {@link #symbol} keys of base symbols that were modified or removed
     * @param affected        affected symbols of the base revision
     */
    public List<EndpointImpact> calculate(List<IndexedEndpoint> base, List<IndexedEndpoint> head,
                                          Set<String> changedHandlers, List<AffectedSymbol> affected) {
        Map<String, IndexedEndpoint> before = byKey(base);
        Map<String, IndexedEndpoint> after = byKey(head);
        Map<String, AffectedSymbol> affectedBySymbol = new LinkedHashMap<>();
        for (AffectedSymbol a : affected) affectedBySymbol.putIfAbsent(symbol(a.path(), a.qualifiedName()), a);

        List<EndpointImpact> out = new ArrayList<>();
        for (var e : before.entrySet()) {
            IndexedEndpoint b = e.getValue();
            String handler = symbol(b.path(), b.handlerQualifiedName());
            if (!after.containsKey(e.getKey())) {
                out.add(direct(b, REMOVED));
            } else if (changedHandlers.contains(handler)) {
                out.add(direct(b, MODIFIED));
            } else if (affectedBySymbol.containsKey(handler)) {
                AffectedSymbol a = affectedBySymbol.get(handler);
                out.add(new EndpointImpact(AFFECTED, b.label(), b.handlerQualifiedName(), b.path(), b.line(),
                        b.framework(), b.reason(), a.distance(), a.certain(), a.confidence(), a.chain()));
            }
        }
        for (var e : after.entrySet()) {
            if (!before.containsKey(e.getKey())) out.add(direct(e.getValue(), ADDED));
        }
        out.sort(Comparator.comparingInt((EndpointImpact x) -> ORDER.indexOf(x.change()))
                .thenComparing(EndpointImpact::label).thenComparing(EndpointImpact::handler));
        return List.copyOf(out);
    }

    /** The key that identifies a declaration across the analysis: its file and qualified name. */
    public static String symbol(String path, String qualifiedName) {
        return path + "|" + qualifiedName;
    }

    private static EndpointImpact direct(IndexedEndpoint e, String change) {
        return new EndpointImpact(change, e.label(), e.handlerQualifiedName(), e.path(), e.line(), e.framework(),
                e.reason(), 0, true, 1.0, List.of());
    }

    private static Map<String, IndexedEndpoint> byKey(List<IndexedEndpoint> endpoints) {
        Map<String, IndexedEndpoint> out = new LinkedHashMap<>();
        for (IndexedEndpoint e : endpoints) {
            String key = e.route().isEmpty() ? e.httpMethod() + " ?" + e.handlerQualifiedName()
                    : e.httpMethod() + " " + e.route();
            out.putIfAbsent(key, e);
        }
        return out;
    }
}
