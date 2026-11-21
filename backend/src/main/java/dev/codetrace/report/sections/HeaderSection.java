package dev.codetrace.report.sections;

import static dev.codetrace.report.MarkdownText.code;
import static dev.codetrace.report.MarkdownText.shortHash;

import dev.codetrace.impact.ImpactReportDao.Report;
import dev.codetrace.report.MarkdownText;
import dev.codetrace.report.ReportData;
import dev.codetrace.report.ReportSection;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;

/** Title, risk headline and the provenance that makes the report reproducible: revisions, snapshots, algorithm. */
@Component
public class HeaderSection implements ReportSection {
    @Override
    public int order() {
        return 10;
    }

    @Override
    public String id() {
        return "header";
    }

    @Override
    public String render(ReportData d) {
        Report r = d.report();
        String completed = r.completedAt() == null ? "—"
                : DateTimeFormatter.ISO_INSTANT.format(r.completedAt().withOffsetSameInstant(ZoneOffset.UTC).toInstant());
        return "# Change impact report\n\n"
                + "**" + r.riskLevel() + "** risk (" + r.riskScore() + "/100) for " + code(d.repositoryName()) + ": "
                + code(shortHash(r.baseCommit())) + " → " + code(shortHash(r.headCommit())) + "\n\n"
                + MarkdownText.table(List.of("Field", "Value"), List.of(
                List.of("Repository", code(d.repositoryName())),
                List.of("Base revision", code(r.baseCommit())),
                List.of("Head revision", code(r.headCommit())),
                List.of("Base graph snapshot", code(r.baseSnapshotId().toString())),
                List.of("Head graph snapshot", code(r.headSnapshotId().toString())),
                List.of("Algorithm", code(r.algorithmVersion())),
                List.of("Traversal depth", String.valueOf(r.depth())),
                List.of("Completed (UTC)", code(completed)),
                List.of("Report id", code(r.id().toString())))) + "\n";
    }
}
