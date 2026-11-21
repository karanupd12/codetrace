package dev.codetrace.ingestion.service;

import dev.codetrace.ingestion.model.IndexResult;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor;
import dev.codetrace.ingestion.repository.IndexResultRepository;
import dev.codetrace.jobs.service.JobService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores an index result and completes its job in one transaction, so a crash leaves neither half behind. */
@Service
public class IndexResultService {
    private final IndexResultRepository results;
    private final JobService jobs;

    public IndexResultService(IndexResultRepository results, JobService jobs) {
        this.results = results;
        this.jobs = jobs;
    }

    @Transactional
    public void store(UUID jobId, UUID repositoryId, String commitHash, IndexResult result) {
        int stored = results.write(jobId, repositoryId, commitHash, result);
        jobs.completeIndex(jobId, result.filesParsed(), result.filesSkipped(), stored, result.parserVersion(),
                JavaSymbolExtractor.EXTRACTION_VERSION);
    }
}
