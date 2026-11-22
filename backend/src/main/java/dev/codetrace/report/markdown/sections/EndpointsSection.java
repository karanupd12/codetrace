package dev.codetrace.report.markdown.sections;

import static dev.codetrace.report.markdown.MarkdownText.code;

import dev.codetrace.impact.model.Finding;
import dev.codetrace.report.markdown.ReportData;
import dev.codetrace.report.markdown.ReportSection;
import java.util.List;
import org.springframework.stereotype.Component;

/** HTTP endpoints the change adds, removes, modifies or affects: the contract its clients see. */
@Component
public class EndpointsSection implements ReportSection {
    static final int MAX_ROWS = 50;

    @Override
    public int order() {
        return 35;
    }

    @Override
    public String id() {
        return "endpoints";
    }

    @Override
    public String render(ReportData d) {
        List<Finding> endpoints = d.endpoints();
        if (endpoints.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## HTTP endpoints (").append(endpoints.size()).append(")\n\n");
        sb.append("Endpoints declared with Spring MVC or JAX-RS annotations. *Removed* endpoints break their clients; ")
                .append("*modified* ones have a changed handler; *affected* ones have a handler that depends on the change. ")
                .append("Routes that are not written as literals are shown as such and matched by their handler.\n\n");
        sb.append("| Change | Endpoint | Handler | Location |\n|---|---|---|---|\n");
        for (Finding f : endpoints.stream().limit(MAX_ROWS).toList()) {
            String change = f.changeKind() != null ? f.changeKind()
                    : "AFFECTED (distance " + f.distance() + (Boolean.TRUE.equals(f.certain()) ? ")" : ", uncertain)");
            sb.append("| ").append(change).append(" | ").append(code(f.name())).append(" | ")
                    .append(code(f.changedSymbol())).append(" | ").append(code(f.path() + ":" + f.line())).append(" |\n");
        }
        if (endpoints.size() > MAX_ROWS) sb.append("\n_… and ").append(endpoints.size() - MAX_ROWS).append(" more._\n");
        return sb.append('\n').toString();
    }
}
