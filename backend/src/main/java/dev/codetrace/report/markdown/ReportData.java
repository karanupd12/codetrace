package dev.codetrace.report.markdown;

import dev.codetrace.impact.model.ImpactReport;
import dev.codetrace.impact.model.Finding;
import dev.codetrace.impact.risk.RiskSignal.RiskFactor;
import java.util.List;

/** Everything a report section may draw on: the stored report header and its findings, nothing recomputed. */
public record ReportData(ImpactReport report, String repositoryName, List<RiskFactor> factors, List<Finding> changed,
                         List<Finding> affected, List<Finding> possible, List<Finding> endpoints, List<String> notes) {

    public List<Finding> productionAffected() {
        return affected.stream().filter(f -> !f.test()).toList();
    }

    public List<Finding> testsAffected() {
        return affected.stream().filter(Finding::test).toList();
    }
}
