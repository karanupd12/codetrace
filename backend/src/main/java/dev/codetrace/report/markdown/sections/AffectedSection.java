package dev.codetrace.report.markdown.sections;

import static dev.codetrace.report.markdown.MarkdownText.code;

import dev.codetrace.impact.service.ImpactReportService.Finding;
import dev.codetrace.report.markdown.MarkdownText;
import dev.codetrace.report.markdown.ReportData;
import dev.codetrace.report.markdown.ReportSection;
import java.util.List;
import org.springframework.stereotype.Component;

/** Who depends on the change: production code first, then the tests worth running. */
@Component
public class AffectedSection implements ReportSection {
    static final int MAX_ROWS = 100;

    @Override
    public int order() {
        return 40;
    }

    @Override
    public String id() {
        return "affected";
    }

    @Override
    public String render(ReportData d) {
        List<Finding> production = d.productionAffected();
        List<Finding> tests = d.testsAffected();
        StringBuilder sb = new StringBuilder("## Affected symbols\n\n");
        sb.append("Found by following dependencies backwards from the changed symbols, up to ").append(d.report().depth())
                .append(" hop(s). **Distance** is the number of hops from the nearest changed symbol. A symbol is **proven** when every link on its path is resolved, and **uncertain** when the path crosses an ambiguous link.\n\n");

        sb.append("### Production code (").append(production.size()).append(")\n\n");
        if (production.isEmpty()) {
            sb.append("_No production symbol depends on the changed code._\n\n");
        } else {
            List<List<String>> rows = production.stream().limit(MAX_ROWS).map(f -> List.of(code(f.name()), code(f.module()),
                    String.valueOf(f.distance()), certainty(f), code(f.changedSymbol()))).toList();
            sb.append(MarkdownText.table(List.of("Symbol", "Module", "Distance", "Certainty", "Because of"), rows));
            if (production.size() > MAX_ROWS) sb.append("\n_… and ").append(production.size() - MAX_ROWS).append(" more; the findings API lists all of them._\n");
            sb.append('\n');
        }

        sb.append("### Tests to run (").append(tests.size()).append(")\n\n");
        if (tests.isEmpty()) {
            sb.append("_No test in the dependency graph depends on the changed code._\n\n");
        } else {
            for (Finding f : tests.stream().limit(MAX_ROWS).toList()) {
                sb.append("- ").append(code(f.name())).append(" in ").append(code(f.path() + ":" + f.line()))
                        .append(" (distance ").append(f.distance()).append(", ").append(certainty(f)).append(")\n");
            }
            if (tests.size() > MAX_ROWS) sb.append("\n_… and ").append(tests.size() - MAX_ROWS).append(" more._\n");
            sb.append('\n');
        }
        return sb.toString();
    }

    static String certainty(Finding f) {
        return Boolean.TRUE.equals(f.certain()) ? "proven" : "uncertain";
    }
}
