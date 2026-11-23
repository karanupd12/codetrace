package dev.codetrace.impact.model;

import java.util.List;
import java.util.Map;

/**
 * Counts over a completed report's findings, for charts and headline numbers without paging through findings.
 *
 * @param byType        findings per type (CHANGED, AFFECTED, POSSIBLE, ENDPOINT), every type present
 * @param changedByKind changed symbols per change kind (ADDED, MODIFIED, REMOVED)
 * @param endpoints     endpoint findings per change kind; endpoints reached without a contract change count as AFFECTED
 * @param modules       affected code per module, largest first, at most {@link #MAX_MODULES}
 * @param otherModules  affected findings in modules beyond the first {@link #MAX_MODULES}
 * @param distances     affected code per number of steps from the change, nearest first
 * @param uncertain     affected findings whose chain includes at least one best-guess link
 */
public record ImpactSummary(Map<String, Long> byType, Map<String, Long> changedByKind, Map<String, Long> endpoints,
                            List<ModuleCount> modules, long otherModules, List<DistanceCount> distances,
                            long uncertain) {
    public static final int MAX_MODULES = 8;

    public record ModuleCount(String module, long production, long tests) {
        public long total() {
            return production + tests;
        }
    }

    public record DistanceCount(int distance, long production, long tests) {
    }

    /** One group of findings sharing every summarised attribute. */
    public record Group(String findingType, String module, boolean test, Integer distance, Boolean certain,
                        String changeKind, long count) {
    }
}
