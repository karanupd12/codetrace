package dev.codetrace.graph.service;

import dev.codetrace.graph.engine.GraphBuilder;
import dev.codetrace.graph.repository.GraphInputRepository;
import dev.codetrace.graph.repository.GraphIntegrityRepository;
import dev.codetrace.graph.repository.GraphSnapshotRepository;
import dev.codetrace.graph.repository.GraphWriteRepository;
import dev.codetrace.config.GraphProperties;
import dev.codetrace.graph.model.GraphModel.Input;
import dev.codetrace.graph.model.GraphModel.Limits;
import dev.codetrace.graph.model.GraphModel.Result;
import dev.codetrace.graph.model.Snapshot;
import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.jobs.service.JobService;
import dev.codetrace.jobs.model.IndexedRevision;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.jobs.JobHandler;
import dev.codetrace.jobs.model.JobOperation;
import dev.codetrace.jobs.model.JobStep;
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
    private static final Logger log = LoggerFactory.getLogger(GraphBuildHandler.class);

    private final JobService jobs;
    private final GraphSnapshotRepository snapshots;
    private final GraphInputRepository loader;
    private final GraphWriteRepository writer;
    private final GraphIntegrityRepository validator;
    private final GraphProperties props;
    private final GraphBuilder builder = new GraphBuilder();

    public GraphBuildHandler(JobService jobs, GraphSnapshotRepository snapshots, GraphInputRepository loader, GraphWriteRepository writer,
                             GraphIntegrityRepository validator, GraphProperties props) {
        this.jobs = jobs;
        this.snapshots = snapshots;
        this.loader = loader;
        this.writer = writer;
        this.validator = validator;
        this.props = props;
    }

    @Override
    public JobOperation operation() {
        return JobOperation.GRAPH_BUILD;
    }

    @Override
    public void handle(Job job) {
        IndexedRevision indexed = jobs.findSucceededIndex(job.repositoryId(), job.commitHash()).orElseThrow(
                () -> new JobFailureException(Category.INVALID_SOURCE, "This revision has not been indexed."));
        if (!indexed.isCurrent()) {
            throw new JobFailureException(Category.INVALID_SOURCE, "This revision was indexed by an older extractor; re-index it.");
        }
        Snapshot snapshot = snapshots.createOrGet(job.repositoryId(), job.commitHash(), indexed.parserVersion(),
                GraphBuilder.VERSION, indexed.jobId()).snapshot();
        if (snapshot.published()) {
            log.info("job {} snapshot {} already published; nothing to do", job.id(), snapshot.id());
            return;
        }
        if (!snapshots.begin(snapshot.id())) return; // published concurrently by another worker

        try {
            jobs.progress(job, JobStep.LOADING, "Loading the scanned code");
            Input input = loader.load(indexed.jobId());
            jobs.progress(job, JobStep.BUILDING_MAP, "Building the dependency map");
            Result graph = builder.build(snapshot.id(), input, new Limits(props.maxNodes(), props.maxEdges()));
            jobs.progress(job, JobStep.SAVING_MAP, "Saving the map");
            writer.write(snapshot, graph);
            writer.refreshStatistics();

            jobs.progress(job, JobStep.CHECKING_MAP, "Checking the map");
            List<String> violations = validator.validate(snapshot.id(), graph.nodes().size(), graph.edges().size());
            if (!violations.isEmpty()) {
                throw new JobFailureException(Category.GRAPH_INVALID, "Snapshot failed validation: " + String.join("; ", violations));
            }
            if (!snapshots.publish(snapshot.id())) {
                throw new JobFailureException(Category.INTERNAL, "Snapshot could not be published (unexpected state).");
            }
            jobs.progress(job, JobStep.MAP_READY, "Dependency map ready: " + String.format(java.util.Locale.ROOT, "%,d", graph.nodes().size()) + " items and "
                    + String.format(java.util.Locale.ROOT, "%,d", graph.edges().size()) + " links",
                    java.util.Map.of("nodes", graph.nodes().size(), "edges", graph.edges().size()));
            log.info("job {} published snapshot {}: {} nodes, {} edges", job.id(), snapshot.id(), graph.nodes().size(),
                    graph.edges().size());
        } catch (DataAccessException e) {
            // Infrastructure fault: leave the snapshot for the retried job, which clears it in begin().
            throw e;
        } catch (RuntimeException e) {
            String reason = JobFailureException.safeReason(e);
            snapshots.markFailed(snapshot.id(), reason);
            throw e;
        }
    }

    @Override
    public void onPermanentFailure(Job job) {
        // handle() already marked the snapshot FAILED for failures after begin(); nothing else to roll back.
    }
}
