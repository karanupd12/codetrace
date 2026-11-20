package dev.codetrace.graph;

import dev.codetrace.config.GraphProperties;
import dev.codetrace.graph.GraphModel.Input;
import dev.codetrace.graph.GraphModel.Limits;
import dev.codetrace.graph.GraphModel.Result;
import dev.codetrace.graph.GraphSnapshotDao.Snapshot;
import dev.codetrace.ingestion.IngestionException;
import dev.codetrace.ingestion.IngestionException.Category;
import dev.codetrace.jobs.JobDao;
import dev.codetrace.jobs.JobDao.IndexedRevision;
import dev.codetrace.jobs.JobDao.Job;
import dev.codetrace.jobs.JobHandler;
import dev.codetrace.parser.JavaSymbolExtractor;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * GRAPH_BUILD: resolve an INDEX job's facts into a graph snapshot. Lifecycle: BUILDING (earlier partial rows cleared) ->
 * rows written in one transaction -> VALIDATING -> invariants checked -> PUBLISHED by a single atomic update. Any
 * failure leaves the snapshot FAILED with no graph rows, so a half-built graph is never visible. Safe to re-run: a
 * PUBLISHED snapshot is left untouched.
 */
@Component
@Profile("worker")
public class GraphBuildHandler implements JobHandler {
    public static final String OPERATION = "GRAPH_BUILD";
    private static final Logger log = LoggerFactory.getLogger(GraphBuildHandler.class);

    private final JobDao jobs;
    private final GraphSnapshotDao snapshots;
    private final GraphInputLoader loader;
    private final GraphWriter writer;
    private final GraphValidator validator;
    private final GraphProperties props;
    private final GraphBuilder builder = new GraphBuilder();

    public GraphBuildHandler(JobDao jobs, GraphSnapshotDao snapshots, GraphInputLoader loader, GraphWriter writer,
                             GraphValidator validator, GraphProperties props) {
        this.jobs = jobs;
        this.snapshots = snapshots;
        this.loader = loader;
        this.writer = writer;
        this.validator = validator;
        this.props = props;
    }

    @Override
    public String operation() {
        return OPERATION;
    }

    @Override
    public void handle(Job job) {
        IndexedRevision indexed = jobs.findSucceededIndex(job.repositoryId(), job.commitHash()).orElseThrow(
                () -> new IngestionException(Category.INVALID_SOURCE, "This revision has not been indexed."));
        if (indexed.extractionVersion() == null || indexed.extractionVersion() < JavaSymbolExtractor.EXTRACTION_VERSION) {
            throw new IngestionException(Category.INVALID_SOURCE, "This revision was indexed by an older extractor; re-index it.");
        }
        Snapshot snapshot = snapshots.createOrGet(job.repositoryId(), job.commitHash(), indexed.parserVersion(),
                GraphBuilder.VERSION, indexed.jobId()).snapshot();
        if (snapshot.published()) {
            log.info("job {} snapshot {} already published; nothing to do", job.id(), snapshot.id());
            return;
        }
        if (!snapshots.begin(snapshot.id())) return; // published concurrently by another worker

        try {
            Input input = loader.load(indexed.jobId());
            Result graph = builder.build(snapshot.id(), input, new Limits(props.maxNodes(), props.maxEdges()));
            writer.write(snapshot, graph);

            List<String> violations = validator.validate(snapshot.id(), graph.nodes().size(), graph.edges().size());
            if (!violations.isEmpty()) {
                throw new IngestionException(Category.GRAPH_INVALID, "Snapshot failed validation: " + String.join("; ", violations));
            }
            if (!snapshots.publish(snapshot.id())) {
                throw new IngestionException(Category.INTERNAL, "Snapshot could not be published (unexpected state).");
            }
            log.info("job {} published snapshot {}: {} nodes, {} edges", job.id(), snapshot.id(), graph.nodes().size(),
                    graph.edges().size());
        } catch (DataAccessException e) {
            // Infrastructure fault: leave the snapshot for the retried job, which clears it in begin().
            throw e;
        } catch (RuntimeException e) {
            String reason = e instanceof IngestionException ie ? ie.getMessage() : "Unexpected error (" + e.getClass().getSimpleName() + ").";
            snapshots.markFailed(snapshot.id(), reason);
            throw e;
        }
    }

    @Override
    public void onPermanentFailure(Job job) {
        // handle() already marked the snapshot FAILED for failures after begin(); nothing else to roll back.
    }
}
