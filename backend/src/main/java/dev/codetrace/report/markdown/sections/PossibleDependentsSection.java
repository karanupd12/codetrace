package dev.codetrace.report.markdown.sections;

import static dev.codetrace.report.markdown.MarkdownText.code;

import dev.codetrace.impact.service.ImpactReportService.Finding;
import dev.codetrace.report.markdown.ReportData;
import dev.codetrace.report.markdown.ReportSection;
import java.util.List;
import org.springframework.stereotype.Component;

/** Calls the analysis could not attribute to a type; they may or may not use a changed method. Never counted as affected. */
@Component
public class PossibleDependentsSection implements ReportSection {
    static final int MAX_ROWS = 50;

    @Override
    public int order() {
        return 60;
    }

    @Override
    public String id() {
        return "possible-dependents";
    }

    @Override
    public String render(ReportData d) {
        List<Finding> possible = d.possible();
        if (possible.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## Possible dependents (not proven) (").append(possible.size()).append(")\n\n");
        sb.append("These calls use a method with the same name and argument count as a changed method, but the type of the object they call it on could not be determined (for example the result of another call). Check them by hand; they are **not** included in the affected counts or the risk score's blast-radius signal.\n\n");
        for (Finding f : possible.stream().limit(MAX_ROWS).toList()) {
            sb.append("- ").append(code(f.name())).append(" at ").append(code(f.path() + ":" + f.line()))
                    .append(" may call ").append(code(f.changedSymbol())).append('\n');
        }
        if (possible.size() > MAX_ROWS) sb.append("\n_… and ").append(possible.size() - MAX_ROWS).append(" more._\n");
        return sb.append('\n').toString();
    }
}
