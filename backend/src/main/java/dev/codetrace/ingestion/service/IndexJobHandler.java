package dev.codetrace.ingestion.service;

import dev.codetrace.ingestion.git.GitSourceManager;
import dev.codetrace.ingestion.indexer.JavaIndexer;
import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.jobs.repository.JobRepository.Job;
import dev.codetrace.jobs.JobHandler;
import dev.codetrace.source.repository.SourceRepositoryRepository;
import dev.codetrace.source.repository.SourceRepositoryRepository.Repo;
import dev.codetrace.ingestion.model.IndexResult;
import dev.codetrace.ingestion.repository.IndexResultRepository;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** INDEX: parse one pinned commit and store its symbols, imports, references and diagnostics atomically. */
@Component
@Profile("worker")
public class IndexJobHandler implements JobHandler {
    public static final String OPERATION = "INDEX";
    private static final Logger log = LoggerFactory.getLogger(IndexJobHandler.class);

    private final SourceRepositoryRepository repos;
    private final GitSourceManager git;
    private final JavaIndexer indexer;
    private final IndexResultRepository writer;

    public IndexJobHandler(SourceRepositoryRepository repos, GitSourceManager git, JavaIndexer indexer, IndexResultRepository writer) {
        this.repos = repos;
        this.git = git;
        this.indexer = indexer;
        this.writer = writer;
    }

    @Override
    public String operation() {
        return OPERATION;
    }

    @Override
    public void handle(Job job) {
        Repo repo = repos.find(job.repositoryId()).orElseThrow(
                () -> new JobFailureException(Category.INVALID_SOURCE, "Repository no longer exists."));
        try (Repository r = git.open(repo)) {
            IndexResult result = indexer.index(r, job.commitHash());
            writer.write(job.id(), repo.id(), job.commitHash(), result); // also marks the job SUCCEEDED
            log.info("job {} indexed {} files, {} symbols", job.id(), result.filesParsed(), result.symbols().size());
        }
    }
}
