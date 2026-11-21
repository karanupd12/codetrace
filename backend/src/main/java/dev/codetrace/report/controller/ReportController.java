package dev.codetrace.report.controller;

import dev.codetrace.report.service.ReportDocumentService;
import java.nio.charset.StandardCharsets;
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

    private final ReportDocumentService documents;

    public ReportController(ReportDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/impact-reports/{reportId}/report.md")
    public ResponseEntity<String> markdown(@PathVariable UUID reportId) {
        ReportDocumentService.Document doc = documents.markdown(reportId);
        return ResponseEntity.ok()
                .contentType(MARKDOWN)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + doc.filename() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(doc.markdown());
    }
}
