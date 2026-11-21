package dev.codetrace.report;

import dev.codetrace.impact.ImpactReportDao.Report;
import dev.codetrace.impact.ImpactService.Finding;
import dev.codetrace.impact.RiskSignal.RiskFactor;
import java.util.List;

/** Everything a report section may draw on: the stored report header and its findings, nothing recomputed. */
public record ReportData(Report report, String repositoryName, List<RiskFactor> factors, List<Finding> changed,
                         List<Finding> affected, List<Finding> possible) {

    public List<Finding> productionAffected() {
        return affected.stream().filter(f -> !f.test()).toList();
    }

    public List<Finding> testsAffected() {
        return affected.stream().filter(Finding::test).toList();
    }
}
