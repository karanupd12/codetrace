package dev.codetrace.report.service;

import dev.codetrace.common.util.Commits;
import dev.codetrace.impact.model.Finding;
import dev.codetrace.impact.model.ImpactReport;
import dev.codetrace.impact.service.ImpactReportService;
import dev.codetrace.report.markdown.MarkdownReportRenderer;
import dev.codetrace.report.markdown.ReportData;
import dev.codetrace.source.model.SourceRepository;
import dev.codetrace.source.service.SourceRepositoryService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Turns a completed impact report into its Markdown document. */
@Service
public class ReportDocumentService {
    /** The rendered document and the file name it should be saved under. */
    public record Document(String filename, String markdown) {
    }

    private final ImpactReportService impact;
    private final SourceRepositoryService repositories;
    private final MarkdownReportRenderer renderer;

    public ReportDocumentService(ImpactReportService impact, SourceRepositoryService repositories,
                                 MarkdownReportRenderer renderer) {
        this.impact = impact;
        this.repositories = repositories;
        this.renderer = renderer;
    }

    /** 404 for an unknown report, 409 until it is completed. */
    public Document markdown(UUID reportId) {
        ImpactReport report = impact.completed(reportId);
        List<Finding> all = impact.allFindings(report.id());
        ReportData data = new ReportData(report,
                repositories.find(report.repositoryId()).map(SourceRepository::displayName).orElse("repository"),
                impact.riskFactors(report), ofType(all, "CHANGED"), ofType(all, "AFFECTED"), ofType(all, "POSSIBLE"));
        String filename = "impact-" + Commits.shortHash(report.baseCommit()) + "-" + Commits.shortHash(report.headCommit()) + ".md";
        return new Document(filename, renderer.render(data));
    }

    private static List<Finding> ofType(List<Finding> all, String type) {
        return all.stream().filter(f -> f.findingType().equals(type)).toList();
    }
}
