package dev.codetrace.impact.repository;

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
    public record Report(UUID id, UUID repositoryId, String baseCommit, String headCommit, UUID baseSnapshotId,
                         UUID headSnapshotId, String algorithmVersion, int depth, UUID jobId, String status,
                         Integer riskScore, String riskLevel, String riskFactorsJson, Integer changedCount,
                         Integer affectedCount, Integer testsAffectedCount, Integer possibleCount, Integer unmappedHunks,
                         Integer nonJavaFiles, Boolean truncated, String failureReason, OffsetDateTime createdAt,
                         OffsetDateTime completedAt) {
        public boolean completed() {
            return "COMPLETED".equals(status);
        }
    }

    public record Created(Report report, boolean created) {
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
                on conflict (repository_id, base_snapshot_id, head_snapshot_id, algorithm_version, depth) do nothing""")
                .param("id", UUID.randomUUID()).param("r", repositoryId).param("bc", baseCommit).param("hc", headCommit)
                .param("bs", baseSnapshot).param("hs", headSnapshot).param("alg", algorithmVersion).param("d", depth)
                .param("j", jobId).update();
        Report report = jdbc.sql("select " + COLS + " from impact_report where repository_id = :r and base_snapshot_id = :bs"
                + " and head_snapshot_id = :hs and algorithm_version = :alg and depth = :d")
                .param("r", repositoryId).param("bs", baseSnapshot).param("hs", headSnapshot).param("alg", algorithmVersion)
                .param("d", depth).query(ImpactReportRepository::map).single();
        return new Created(report, inserted == 1);
    }

    public Optional<Report> find(UUID id) {
        return jdbc.sql("select " + COLS + " from impact_report where id = :id").param("id", id)
                .query(ImpactReportRepository::map).optional();
    }

    public Optional<Report> findByJob(UUID jobId) {
        return jdbc.sql("select " + COLS + " from impact_report where job_id = :j").param("j", jobId)
                .query(ImpactReportRepository::map).optional();
    }

    public List<Report> listForRepository(UUID repositoryId, int limit) {
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
                .param("id", id).param("why", reason.length() > 300 ? reason.substring(0, 300) : reason).update();
    }

    /** A failed report whose job is queued again. */
    public void requeue(UUID id) {
        jdbc.sql("update impact_report set status = 'REQUESTED', failure_reason = null where id = :id and status = 'FAILED'")
                .param("id", id).update();
    }

    private static Report map(ResultSet rs, int i) throws SQLException {
        return new Report(rs.getObject("id", UUID.class), rs.getObject("repository_id", UUID.class),
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
}
