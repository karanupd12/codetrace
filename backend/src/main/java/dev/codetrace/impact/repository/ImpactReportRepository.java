package dev.codetrace.impact.repository;

import dev.codetrace.impact.model.ImpactReport;
import dev.codetrace.common.util.Texts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence of impact report headers. Findings are written together with completion by {@link ImpactReportWriteRepository}. */
@Repository
public class ImpactReportRepository {
    public record Created(ImpactReport report, boolean created) {
    }

    private static final String COLS = """
            id, repository_id, base_commit, head_commit, base_snapshot_id, head_snapshot_id, algorithm_version, depth, job_id,
            status, risk_score, risk_level, risk_factors::text as risk_factors, changed_count, affected_count,
            tests_affected_count, possible_count, unmapped_hunks, non_java_files, truncated, failure_reason, created_at,
            completed_at""";

    private final JdbcClient jdbc;

    public ImpactReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One report per (repository, base snapshot, head snapshot, algorithm version, depth); repeated requests share it. */
    public Created createOrGet(UUID repositoryId, String baseCommit, String headCommit, UUID baseSnapshot, UUID headSnapshot,
                               String algorithmVersion, int depth, UUID jobId) {
        int inserted = jdbc.sql("""
                insert into impact_report (id, repository_id, base_commit, head_commit, base_snapshot_id, head_snapshot_id,
                                           algorithm_version, depth, job_id, status)
                values (:id, :r, :bc, :hc, :bs, :hs, :alg, :d, :j, 'REQUESTED')
                on conflict do nothing""") // identity and job id both name the same report; a concurrent twin wins
                .param("id", UUID.randomUUID()).param("r", repositoryId).param("bc", baseCommit).param("hc", headCommit)
                .param("bs", baseSnapshot).param("hs", headSnapshot).param("alg", algorithmVersion).param("d", depth)
                .param("j", jobId).update();
        ImpactReport report = jdbc.sql("select " + COLS + " from impact_report where repository_id = :r and base_snapshot_id = :bs"
                + " and head_snapshot_id = :hs and algorithm_version = :alg and depth = :d")
                .param("r", repositoryId).param("bs", baseSnapshot).param("hs", headSnapshot).param("alg", algorithmVersion)
                .param("d", depth).query(ImpactReportRepository::map).single();
        return new Created(report, inserted == 1);
    }

    public Optional<ImpactReport> find(UUID id) {
        return jdbc.sql("select " + COLS + " from impact_report where id = :id").param("id", id)
                .query(ImpactReportRepository::map).optional();
    }

    public Optional<ImpactReport> findByJob(UUID jobId) {
        return jdbc.sql("select " + COLS + " from impact_report where job_id = :j").param("j", jobId)
                .query(ImpactReportRepository::map).optional();
    }

    public List<ImpactReport> listForRepository(UUID repositoryId, int limit) {
        return jdbc.sql("select " + COLS + " from impact_report where repository_id = :r order by created_at desc limit :l")
                .param("r", repositoryId).param("l", limit).query(ImpactReportRepository::map).list();
    }

    /** Moves a not-yet-completed report to RUNNING. False if it is already completed. */
    public boolean markRunning(UUID id) {
        return jdbc.sql("update impact_report set status = 'RUNNING', failure_reason = null"
                + " where id = :id and status in ('REQUESTED', 'RUNNING', 'FAILED')").param("id", id).update() == 1;
    }

    public void markFailed(UUID id, String reason) {
        jdbc.sql("update impact_report set status = 'FAILED', failure_reason = :why where id = :id and status <> 'COMPLETED'")
                .param("id", id).param("why", Texts.truncate(reason, 300)).update();
    }

    /** A failed report whose job is queued again. */
    public void requeue(UUID id) {
        jdbc.sql("update impact_report set status = 'REQUESTED', failure_reason = null where id = :id and status = 'FAILED'")
                .param("id", id).update();
    }

    private static ImpactReport map(ResultSet rs, int i) throws SQLException {
        return new ImpactReport(rs.getObject("id", UUID.class), rs.getObject("repository_id", UUID.class),
                rs.getString("base_commit"), rs.getString("head_commit"), rs.getObject("base_snapshot_id", UUID.class),
                rs.getObject("head_snapshot_id", UUID.class), rs.getString("algorithm_version"), rs.getInt("depth"),
                rs.getObject("job_id", UUID.class), rs.getString("status"), (Integer) rs.getObject("risk_score"),
                rs.getString("risk_level"), rs.getString("risk_factors"), (Integer) rs.getObject("changed_count"),
                (Integer) rs.getObject("affected_count"), (Integer) rs.getObject("tests_affected_count"),
                (Integer) rs.getObject("possible_count"), (Integer) rs.getObject("unmapped_hunks"),
                (Integer) rs.getObject("non_java_files"), (Boolean) rs.getObject("truncated"),
                rs.getString("failure_reason"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class));
    }

    /**
     * Deletes reports of another algorithm version once a completed report of the current version exists for the same
     * commits and depth. Their findings go with them; the jobs that computed them are removed by the jobs feature.
     */
    public int deleteSuperseded(String currentAlgorithm, int limit) {
        return jdbc.sql("""
                delete from impact_report where id in (
                  select r.id from impact_report r
                   where r.algorithm_version <> :cur
                     and exists (select 1 from impact_report n where n.repository_id = r.repository_id
                                   and n.base_commit = r.base_commit and n.head_commit = r.head_commit
                                   and n.depth = r.depth and n.algorithm_version = :cur and n.status = 'COMPLETED')
                   limit :lim)""")
                .param("cur", currentAlgorithm).param("lim", limit).update();
    }

    /** Deletes FAILED reports older than {@code days}. */
    public int deleteFailedOlderThan(int days, int limit) {
        return jdbc.sql("""
                delete from impact_report where id in (
                  select id from impact_report
                   where status = 'FAILED' and created_at < now() - make_interval(days => :d) limit :lim)""")
                .param("d", days).param("lim", limit).update();
    }
}
