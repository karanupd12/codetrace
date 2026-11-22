package dev.codetrace.system.service;

import dev.codetrace.config.RetentionProperties;
import dev.codetrace.graph.service.GraphSnapshotService;
import dev.codetrace.impact.service.ImpactReportService;
import dev.codetrace.jobs.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Daily clean-up in the worker. Results replaced by a newer builder or algorithm version, old failures and unusable old
 * indexes are deleted in bounded batches; anything a remaining report or graph still refers to is kept.
 */
@Service
@Profile("worker")
public class RetentionService {
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /** How many rows of each kind one run deleted. */
    public record Pruned(int reports, int failedReports, int snapshots, int failedSnapshots, int impactJobs,
                         int staleIndexes, int failedJobs) {
        public int total() {
            return reports + failedReports + snapshots + failedSnapshots + impactJobs + staleIndexes + failedJobs;
        }
    }

    private final RetentionProperties props;
    private final ImpactReportService reports;
    private final GraphSnapshotService snapshots;
    private final JobService jobs;

    public RetentionService(RetentionProperties props, ImpactReportService reports, GraphSnapshotService snapshots,
                            JobService jobs) {
        this.props = props;
        this.reports = reports;
        this.snapshots = snapshots;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${codetrace.retention.cron:0 30 3 * * *}")
    public void scheduled() {
        if (!props.enabled()) return;
        Pruned p = run();
        if (p.total() > 0) log.info("retention removed {}", p);
    }

    /** One clean-up pass. Reports go first, so the snapshots they referred to can go in the same pass. */
    public Pruned run() {
        int n = props.batchSize();
        int reportsRemoved = reports.pruneSuperseded(n);
        int failedReports = reports.pruneFailed(props.failedAfterDays(), n);
        int snapshotsRemoved = snapshots.pruneSuperseded(n);
        int failedSnapshots = snapshots.pruneFailed(props.failedAfterDays(), n);
        int impactJobs = jobs.pruneOrphanImpactJobs(n);
        int staleIndexes = jobs.pruneStaleIndexes(props.staleIndexAfterDays(), n);
        int failedJobs = jobs.pruneFailed(props.failedAfterDays(), n);
        return new Pruned(reportsRemoved, failedReports, snapshotsRemoved, failedSnapshots, impactJobs, staleIndexes,
                failedJobs);
    }
}
