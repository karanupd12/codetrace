package dev.codetrace.report.sections;

import static dev.codetrace.report.MarkdownText.code;

import dev.codetrace.impact.ImpactService.Finding;
import dev.codetrace.report.MarkdownText;
import dev.codetrace.report.ReportData;
import dev.codetrace.report.ReportSection;
import java.util.List;
import org.springframework.stereotype.Component;

/** What the change touched, symbol by symbol. */
@Component
public class ChangedSymbolsSection implements ReportSection {
    static final int MAX_ROWS = 100;

    @Override
    public int order() {
        return 30;
    }

    @Override
    public String id() {
        return "changed-symbols";
    }

    @Override
    public String render(ReportData d) {
        List<Finding> changed = d.changed();
        StringBuilder sb = new StringBuilder("## Changed symbols (").append(changed.size()).append(")\n\n");
        if (changed.isEmpty()) {
            sb.append("_No Java declaration changed._ ");
            if (d.report().unmappedHunks() != null && d.report().unmappedHunks() > 0) {
                sb.append(d.report().unmappedHunks()).append(" changed region(s) touch no declaration (imports, comments, blank lines).");
            }
            return sb.append("\n\n").toString();
        }
        sb.append("`ADDED` symbols are new and cannot have dependents yet. `MODIFIED` and `REMOVED` symbols are the starting points of the impact analysis; a changed signature appears as a removal plus an addition.\n\n");
        List<List<String>> rows = changed.stream().limit(MAX_ROWS)
                .map(f -> List.of(f.changeKind() == null ? "" : f.changeKind(), code(f.name()),
                        code(f.path() + ":" + f.line()), String.valueOf(f.directDependents() == null ? 0 : f.directDependents())))
                .toList();
        sb.append(MarkdownText.table(List.of("Change", "Symbol", "Location", "Direct dependents"), rows));
        if (changed.size() > MAX_ROWS) sb.append("\n_… and ").append(changed.size() - MAX_ROWS).append(" more; the findings API lists all of them._\n");
        return sb.append('\n').toString();
    }
}
