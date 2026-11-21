package dev.codetrace.symbols.service;

import dev.codetrace.common.util.Bounds;
import dev.codetrace.common.web.ApiException;
import dev.codetrace.common.web.Page;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.jobs.model.JobOperation;
import dev.codetrace.jobs.service.JobService;
import dev.codetrace.symbols.model.IndexedSymbol;
import dev.codetrace.symbols.model.ParseDiagnostic;
import dev.codetrace.symbols.repository.SymbolRepository;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Reading what an index job extracted. Every page is bounded. */
@Service
public class SymbolService {
    public static final int MAX_LIMIT = 200;
    private static final Set<String> KINDS = Set.of("TYPE", "METHOD", "FIELD");

    private final SymbolRepository symbols;
    private final JobService jobs;

    public SymbolService(SymbolRepository symbols, JobService jobs) {
        this.symbols = symbols;
        this.jobs = jobs;
    }

    public Page<IndexedSymbol> search(UUID jobId, String kind, String query, int limit, int offset) {
        requireIndexJob(jobId);
        if (kind != null && !kind.isBlank() && !KINDS.contains(kind.toUpperCase(Locale.ROOT))) {
            throw ApiException.badRequest("kind must be TYPE, METHOD or FIELD.");
        }
        return symbols.search(jobId, kind, query, Bounds.limit(limit, MAX_LIMIT), Bounds.offset(offset));
    }

    public Page<ParseDiagnostic> diagnostics(UUID jobId, int limit, int offset) {
        requireIndexJob(jobId);
        return symbols.diagnostics(jobId, Bounds.limit(limit, MAX_LIMIT), Bounds.offset(offset));
    }

    private void requireIndexJob(UUID jobId) {
        Job job = jobs.get(jobId);
        if (!JobOperation.INDEX.name().equals(job.operation())) {
            throw ApiException.badRequest("Job is not an index job.");
        }
    }
}
