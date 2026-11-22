package dev.codetrace.ingestion.service;

import dev.codetrace.config.IngestionProperties;
import dev.codetrace.ingestion.git.GitSourceManager;
import dev.codetrace.ingestion.indexer.JavaIndexer;
import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.jobs.JobHandler;
import dev.codetrace.jobs.model.JobOperation;
import dev.codetrace.source.service.SourceRepositoryService;
import dev.codetrace.source.model.SourceRepository;
import dev.codetrace.ingestion.model.IndexResult;
import dev.codetrace.ingestion.model.ReusableIndex;
import dev.codetrace.jobs.model.JobStep;
import dev.codetrace.jobs.service.JobService;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * INDEX: parse one pinned commit and store its symbols, imports, references and diagnostics atomically. Files whose
 * content is unchanged since the latest compatible index of the same repository are copied instead of parsed.
 */
@Component
@Profile("worker")
public class IndexJobHandler implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(IndexJobHandler.class);

    private final SourceRepositoryService repositories;
    private final GitSourceManager git;
    private final JavaIndexer indexer;
    private final IndexResultService results;
    private final IngestionProperties props;
    private final JobService jobs;

    public IndexJobHandler(SourceRepositoryService repositories, GitSourceManager git, JavaIndexer indexer,
                           IndexResultService results, IngestionProperties props, JobService jobs) {
        this.jobs = jobs;
        this.repositories = repositories;
        this.git = git;
        this.indexer = indexer;
        this.results = results;
        this.props = props;
    }

    @Override
    public JobOperation operation() {
        return JobOperation.INDEX;
    }

    @Override
    public void handle(Job job) {
        SourceRepository repo = repositories.find(job.repositoryId()).orElseThrow(
                () -> new JobFailureException(Category.INVALID_SOURCE, "Repository no longer exists."));
        jobs.progress(job, JobStep.READING, "Reading the code");
        try (Repository r = git.open(repo, List.of(job.commitHash()))) {
            ReusableIndex reusable = props.incremental() ? results.reusableFor(repo.id(), job.commitHash(), indexer.javaBlobIds(r, job.commitHash()))
                    : ReusableIndex.NONE;
            IndexResult result = indexer.index(r, job.commitHash(), reusable);
            int files = result.filesParsed() + result.filesSkipped();
            String reusedNote = result.reused().isEmpty() ? "" : " (" + String.format(java.util.Locale.ROOT, "%,d", result.reused().size()) + " unchanged, reused)";
            jobs.progress(job, JobStep.READ, "Read " + String.format(java.util.Locale.ROOT, "%,d", files) + " files" + reusedNote,
                    Map.of("files", files, "reused", result.reused().size(), "parsed", result.filesParsed()));
            jobs.progress(job, JobStep.SAVING, "Saving what was found");
            results.store(job.id(), repo.id(), job.commitHash(), result); // also marks the job SUCCEEDED
            jobs.progress(job, JobStep.DONE, "Code scanned");
            log.info("job {} indexed {} files ({} reused), {} parsed symbols", job.id(), result.filesParsed(),
                    result.reused().size(), result.symbols().size());
        }
    }
}
