package dev.codetrace.report.sections;

import static dev.codetrace.report.MarkdownText.code;
import static dev.codetrace.report.MarkdownText.plain;

import dev.codetrace.impact.ImpactAnalysis.Hop;
import dev.codetrace.impact.ImpactService.Finding;
import dev.codetrace.report.ReportData;
import dev.codetrace.report.ReportSection;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** The chain of dependency links behind the nearest findings, each with the source line that proves it. */
@Component
public class EvidenceSection implements ReportSection {
    static final int MAX_FINDINGS = 15;

    @Override
    public int order() {
        return 50;
    }

    @Override
    public String id() {
        return "evidence";
    }

    @Override
    public String render(ReportData d) {
        List<Finding> shown = d.affected().stream().filter(f -> !f.chain().isEmpty())
                .sorted(Comparator.comparing((Finding f) -> f.distance() == null ? 0 : f.distance())
                        .thenComparing(Finding::name))
                .limit(MAX_FINDINGS).toList();
        if (shown.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## Evidence\n\n");
        sb.append("How the nearest findings reach a changed symbol. Each line is one dependency link with the source location that justifies it.\n\n");
        for (Finding f : shown) {
            sb.append("**").append(code(f.name())).append("**\n\n```text\n");
            for (Hop h : f.chain()) {
                sb.append(plain(h.from())).append("  →  ").append(plain(h.to())).append("\n    ")
                        .append(plain(h.edgeType())).append(", ").append(plain(h.resolution().toLowerCase()))
                        .append(String.format(java.util.Locale.ROOT, ", confidence %.2f", h.confidence())).append(" at ")
                        .append(plain(h.path())).append(':').append(h.line()).append('\n');
            }
            sb.append("```\n\n");
        }
        if (d.affected().size() > shown.size()) {
            sb.append("_Evidence for the remaining findings is available through the findings API._\n\n");
        }
        return sb.toString();
    }
}
