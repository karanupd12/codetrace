package dev.codetrace.report.controller;

import dev.codetrace.report.markdown.MarkdownReportRenderer;
import dev.codetrace.report.markdown.MarkdownText;
import dev.codetrace.report.markdown.ReportData;
import dev.codetrace.impact.repository.ImpactFindingRepository;
import dev.codetrace.impact.repository.ImpactReportRepository.Report;
import dev.codetrace.impact.service.ImpactReportService;
import dev.codetrace.impact.service.ImpactReportService.Finding;
import dev.codetrace.source.repository.SourceRepositoryRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the Markdown impact report as a download. */
@RestController
@Profile("api")
@RequestMapping("/api/v1")
public class ReportController {
    private static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final ImpactReportService impact;
    private final ImpactFindingRepository findings;
    private final SourceRepositoryRepository repositories;
    private final MarkdownReportRenderer renderer;

    public ReportController(ImpactReportService impact, ImpactFindingRepository findings, SourceRepositoryRepository repositories,
                            MarkdownReportRenderer renderer) {
        this.impact = impact;
        this.findings = findings;
        this.repositories = repositories;
        this.renderer = renderer;
    }

    @GetMapping("/impact-reports/{reportId}/report.md")
    public ResponseEntity<String> markdown(@PathVariable UUID reportId) {
        Report report = impact.completed(reportId); // 404 unknown, 409 until completed
        List<Finding> all = findings.all(report.id());
        ReportData data = new ReportData(report,
                repositories.find(report.repositoryId()).map(SourceRepositoryRepository.Repo::displayName).orElse("repository"),
                impact.riskFactors(report),
                all.stream().filter(f -> f.findingType().equals("CHANGED")).toList(),
                all.stream().filter(f -> f.findingType().equals("AFFECTED")).toList(),
                all.stream().filter(f -> f.findingType().equals("POSSIBLE")).toList());
        String filename = "impact-" + MarkdownText.shortHash(report.baseCommit()) + "-" + MarkdownText.shortHash(report.headCommit()) + ".md";
        return ResponseEntity.ok()
                .contentType(MARKDOWN)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(renderer.render(data));
    }
}
