package dev.codetrace.report.markdown.sections;

import static dev.codetrace.report.markdown.MarkdownText.cell;

import dev.codetrace.impact.model.ImpactReport;
import dev.codetrace.report.markdown.MarkdownText;
import dev.codetrace.report.markdown.ReportData;
import dev.codetrace.report.markdown.ReportSection;
import java.util.List;
import org.springframework.stereotype.Component;

/** The numbers at a glance and how the risk score was put together. */
@Component
public class SummarySection implements ReportSection {
    @Override
    public int order() {
        return 20;
    }

    @Override
    public String id() {
        return "summary";
    }

    @Override
    public String render(ReportData d) {
        ImpactReport r = d.report();
        StringBuilder sb = new StringBuilder("## Summary\n\n");
        sb.append("- **Changed symbols:** ").append(r.changedCount()).append('\n');
        sb.append("- **Affected production symbols:** ").append(d.productionAffected().size()).append('\n');
        sb.append("- **Affected tests:** ").append(r.testsAffectedCount()).append('\n');
        sb.append("- **Possible dependents (not proven):** ").append(r.possibleCount()).append("\n\n");
        sb.append("### Risk score: ").append(r.riskScore()).append("/100 (").append(r.riskLevel()).append(")\n\n");
        sb.append("The score is the sum of independent signals, capped at 100. Only signals that contributed points raise it.\n\n");
        List<List<String>> rows = d.factors().stream()
                .map(f -> List.of(cell(f.signal()), f.points() + " / " + f.maxPoints(), cell(f.reason()))).toList();
        sb.append(MarkdownText.table(List.of("Signal", "Points", "Finding"), rows)).append('\n');
        return sb.toString();
    }
}
