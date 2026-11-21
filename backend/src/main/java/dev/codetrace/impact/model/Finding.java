package dev.codetrace.impact.model;

import dev.codetrace.impact.model.ImpactAnalysis.Hop;
import java.util.List;

/** One stored finding of a report: a changed, affected or possibly affected symbol, with its evidence chain. */
public record Finding(String findingType, int ordinal, String name, String nodeType, String path, int line,
                      String module, boolean test, String changeKind, Integer directDependents, Integer distance,
                      Boolean certain, Double confidence, String changedSymbol, List<Hop> chain) {
}
