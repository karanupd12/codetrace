package dev.codetrace.symbols;

import dev.codetrace.jobs.JobDao;
import dev.codetrace.system.ApiException;
import dev.codetrace.system.Page;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Interim M2 read API over a job's extracted symbols. In M3 these become snapshot-scoped graph node queries.
 * All results are bounded (limit is capped).
 */
@RestController
@Profile("api")
@RequestMapping("/api/v1/index-jobs/{jobId}")
public class SymbolController {
    private final SymbolDao symbols;
    private final JobDao jobs;

    public SymbolController(SymbolDao symbols, JobDao jobs) {
        this.symbols = symbols;
        this.jobs = jobs;
    }

    @GetMapping("/symbols")
    public Page<SymbolDao.SymbolView> symbols(@PathVariable UUID jobId,
                                              @RequestParam(required = false) String kind,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "50") int limit,
                                              @RequestParam(defaultValue = "0") int offset) {
        requireIndexJob(jobId);
        if (kind != null && !kind.isBlank() && !java.util.Set.of("TYPE", "METHOD", "FIELD").contains(kind.toUpperCase())) {
            throw ApiException.badRequest("kind must be TYPE, METHOD or FIELD.");
        }
        return symbols.search(jobId, kind, q, limit, offset);
    }

    @GetMapping("/diagnostics")
    public Page<SymbolDao.DiagnosticView> diagnostics(@PathVariable UUID jobId,
                                                      @RequestParam(defaultValue = "50") int limit,
                                                      @RequestParam(defaultValue = "0") int offset) {
        requireIndexJob(jobId);
        return symbols.diagnostics(jobId, limit, offset);
    }

    private void requireIndexJob(UUID jobId) {
        var job = jobs.find(jobId).orElseThrow(() -> ApiException.notFound("Job not found."));
        if (!"INDEX".equals(job.operation())) {
            throw ApiException.badRequest("Job is not an index job.");
        }
    }
}
