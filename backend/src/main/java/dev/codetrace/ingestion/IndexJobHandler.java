package dev.codetrace.ingestion;

import dev.codetrace.ingestion.IngestionException.Category;
import dev.codetrace.jobs.JobDao.Job;
import dev.codetrace.jobs.JobHandler;
import dev.codetrace.repository.RepositoryDao;
import dev.codetrace.repository.RepositoryDao.Repo;
import dev.codetrace.symbols.IndexResult;
import dev.codetrace.symbols.IndexResultWriter;
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

    private final RepositoryDao repos;
    private final GitSourceManager git;
    private final JavaIndexer indexer;
    private final IndexResultWriter writer;

    public IndexJobHandler(RepositoryDao repos, GitSourceManager git, JavaIndexer indexer, IndexResultWriter writer) {
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
                () -> new IngestionException(Category.INVALID_SOURCE, "Repository no longer exists."));
        try (Repository r = git.open(repo)) {
            IndexResult result = indexer.index(r, job.commitHash());
            writer.write(job.id(), repo.id(), job.commitHash(), result); // also marks the job SUCCEEDED
            log.info("job {} indexed {} files, {} symbols", job.id(), result.filesParsed(), result.symbols().size());
        }
    }
}
