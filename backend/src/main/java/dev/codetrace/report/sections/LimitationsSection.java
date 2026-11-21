package dev.codetrace.report.sections;

import dev.codetrace.impact.ImpactReportDao.Report;
import dev.codetrace.report.ReportData;
import dev.codetrace.report.ReportSection;
import org.springframework.stereotype.Component;

/** How to read the report and what it does not cover, including the limits this particular run hit. */
@Component
public class LimitationsSection implements ReportSection {
    @Override
    public int order() {
        return 70;
    }

    @Override
    public String id() {
        return "limitations";
    }

    @Override
    public String render(ReportData d) {
        Report r = d.report();
        StringBuilder sb = new StringBuilder("## Reading this report and its limits\n\n");
        sb.append("- **Risk levels:** LOW below 25, MEDIUM 25–49, HIGH 50–74, CRITICAL 75 and above. The score ranks changes for review; it does not predict defects.\n");
        sb.append("- **Dependents come from the base revision's graph**, because that is the code that relied on the old behaviour. Symbols added by the change have no dependents yet.\n");
        sb.append("- **Only Java source is analysed.** Other files, configuration, reflection, dependency injection wiring and calls made through dynamically typed values are invisible to the graph.\n");
        sb.append("- **Overrides are not linked:** changing an implementation does not mark callers of the interface method it implements.\n");
        sb.append("- **Resolution is conservative.** Links the analysis cannot prove are ambiguous or unresolved and are reported as such, never guessed.\n");
        if (Boolean.TRUE.equals(r.truncated())) {
            sb.append("- **This analysis reached a configured limit**, so the affected set is a lower bound. Narrow the change or lower the depth.\n");
        }
        if (r.unmappedHunks() != null && r.unmappedHunks() > 0) {
            sb.append("- ").append(r.unmappedHunks()).append(" changed region(s) touch no declaration (imports, comments, blank lines) and are not attributed to any symbol.\n");
        }
        if (r.nonJavaFiles() != null && r.nonJavaFiles() > 0) {
            sb.append("- ").append(r.nonJavaFiles()).append(" non-Java or binary file(s) changed and are not analysed.\n");
        }
        return sb.append('\n').toString();
    }
}
