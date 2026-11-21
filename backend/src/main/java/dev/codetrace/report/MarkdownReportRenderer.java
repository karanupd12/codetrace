package dev.codetrace.report;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Assembles the Markdown impact report from the registered {@link ReportSection}s in their declared order. Adding a
 * section means adding a component; this class does not change.
 */
@Component
public class MarkdownReportRenderer {
    private final List<ReportSection> sections;

    public MarkdownReportRenderer(List<ReportSection> sections) {
        Map<String, Long> counts = sections.stream().collect(Collectors.groupingBy(ReportSection::id, Collectors.counting()));
        counts.forEach((id, n) -> {
            if (n > 1) throw new IllegalStateException("Two report sections share the id " + id);
        });
        this.sections = sections.stream().sorted(Comparator.comparingInt(ReportSection::order).thenComparing(ReportSection::id)).toList();
    }

    public String render(ReportData data) {
        StringBuilder sb = new StringBuilder();
        for (ReportSection s : sections) {
            String part = s.render(data);
            if (part != null && !part.isBlank()) sb.append(part.stripTrailing()).append("\n\n");
        }
        return sb.toString().stripTrailing() + "\n";
    }
}
