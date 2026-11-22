package dev.codetrace.ingestion.service;

import dev.codetrace.ingestion.model.IndexResult;
import dev.codetrace.ingestion.model.ReusableIndex;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor;
import dev.codetrace.ingestion.repository.IndexResultRepository;
import dev.codetrace.jobs.service.JobService;
import dev.codetrace.jobs.model.IndexedRevision;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores an index result and completes its job in one transaction, so a crash leaves neither half behind. Also finds
 * the earlier index whose unchanged files a new index can copy.
 */
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
                JavaSymbolExtractor.EXTRACTION_VERSION, result.reused().size(), result.baseJobId());
    }

    /** How many recent compatible indexes are considered as the base of an incremental index. */
    static final int BASE_CANDIDATES = 5;

    /**
     * The earlier index to copy unchanged files from: among the most recent indexes of other commits written by the
     * current extractor, the one holding the most of {@code blobIds} (the more recent one on a tie), or none.
     */
    public ReusableIndex reusableFor(UUID repositoryId, String commitHash, Collection<String> blobIds) {
        List<UUID> candidates = jobs.findRecentSucceededIndexes(repositoryId, commitHash, JavaSymbolExtractor.PARSER_VERSION,
                JavaSymbolExtractor.EXTRACTION_VERSION, BASE_CANDIDATES).stream().map(IndexedRevision::jobId).toList();
        if (candidates.isEmpty() || blobIds.isEmpty()) return ReusableIndex.NONE;
        Map<UUID, Integer> shared = results.sharedBlobCounts(candidates, blobIds);
        UUID best = null;
        int bestCount = 0;
        for (UUID c : candidates) { // most recent first, so a tie keeps the more recent index
            int n = shared.getOrDefault(c, 0);
            if (n > bestCount) {
                best = c;
                bestCount = n;
            }
        }
        return best == null ? ReusableIndex.NONE : results.reusable(best);
    }
}
