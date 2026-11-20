package dev.codetrace.jobs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Durable job state. PostgreSQL is authoritative; Redis messages only say "look at job X". */
@Repository
public class JobDao {
    public record Job(UUID id, UUID repositoryId, String operation, String status, String commitHash, int attempts,
                      String errorCategory, String errorMessage, OffsetDateTime createdAt, OffsetDateTime startedAt,
                      OffsetDateTime finishedAt, Integer filesParsed, Integer filesSkipped, Integer symbolsExtracted,
                      String parserVersion) {
    }

    public record Created(Job job, boolean created) {
    }

    private static final String COLS = """
            id, repository_id, operation, status, commit_hash, attempts, error_category, error_message, created_at,
            started_at, finished_at, files_parsed, files_skipped, symbols_extracted, parser_version""";

    private final JdbcClient jdbc;

    public JobDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static String syncKey(UUID repositoryId) {
        return "SYNC:" + repositoryId;
    }

    public static String indexKey(UUID repositoryId, String commitHash) {
        return "INDEX:" + repositoryId + ":" + commitHash;
    }

    /** Idempotent create: the same idempotency key always yields the same job row. */
    public Created createOrGet(UUID repositoryId, String operation, String commitHash, String idempotencyKey) {
        int n = jdbc.sql("""
                insert into index_job (id, repository_id, operation, status, commit_hash, idempotency_key)
                values (:id, :r, :op, 'QUEUED', :h, :k) on conflict (idempotency_key) do nothing""")
                .param("id", UUID.randomUUID()).param("r", repositoryId).param("op", operation)
                .param("h", commitHash, Types.VARCHAR).param("k", idempotencyKey).update();
        Job job = jdbc.sql("select " + COLS + " from index_job where idempotency_key = :k").param("k", idempotencyKey)
                .query(JobDao::map).single();
        return new Created(job, n == 1);
    }

    public Optional<Job> find(UUID id) {
        return jdbc.sql("select " + COLS + " from index_job where id = :id").param("id", id)
                .query(JobDao::map).optional();
    }

    public List<Job> listForRepository(UUID repositoryId, int limit) {
        return jdbc.sql("select " + COLS + " from index_job where repository_id = :r order by created_at desc limit :n")
                .param("r", repositoryId).param("n", limit).query(JobDao::map).list();
    }

    /** Re-run a finished job (e.g. re-sync, or retry after failure). Returns true if it was reset. */
    public boolean resetForRerun(UUID id) {
        return jdbc.sql("""
                update index_job set status = 'QUEUED', attempts = 0, error_category = null, error_message = null,
                    started_at = null, finished_at = null, updated_at = now()
                where id = :id and status in ('SUCCEEDED', 'FAILED', 'DEAD_LETTERED')""")
                .param("id", id).update() == 1;
    }

    /**
     * Atomically take the job. Succeeds for QUEUED jobs, or RUNNING jobs whose worker went silent (stale).
     * Everything else (already running, finished) returns false, which makes redelivered messages harmless.
     */
    public boolean claim(UUID id, int staleSeconds) {
        return jdbc.sql("""
                update index_job set status = 'RUNNING', attempts = attempts + 1, started_at = now(),
                    updated_at = now(), error_category = null, error_message = null
                where id = :id and (status = 'QUEUED'
                    or (status = 'RUNNING' and updated_at < now() - (:stale * interval '1 second')))""")
                .param("id", id).param("stale", staleSeconds).update() == 1;
    }

    public void markFailed(UUID id, String category, String message) {
        finish(id, "FAILED", category, message);
    }

    public void markDeadLettered(UUID id, String category, String message) {
        finish(id, "DEAD_LETTERED", category, message);
    }

    public void markSyncSucceeded(UUID id) {
        jdbc.sql("""
                update index_job set status = 'SUCCEEDED', finished_at = now(), updated_at = now(),
                    error_category = null, error_message = null where id = :id""").param("id", id).update();
    }

    /** Back to QUEUED for a delayed retry; the sweeper republishes it after the retry delay. */
    public void markForRetry(UUID id, String category, String message) {
        jdbc.sql("""
                update index_job set status = 'QUEUED', error_category = :c, error_message = :m, updated_at = now()
                where id = :id""").param("id", id).param("c", category).param("m", truncate(message)).update();
    }

    /** QUEUED jobs untouched for a while: touch them and return their ids so they can be republished. */
    public List<UUID> touchStaleQueued(int olderThanSeconds, int limit) {
        return jdbc.sql("""
                update index_job set updated_at = now()
                where id in (select id from index_job where status = 'QUEUED'
                             and updated_at < now() - (:s * interval '1 second') order by updated_at limit :n
                             for update skip locked)
                returning id""")
                .param("s", olderThanSeconds).param("n", limit).query(UUID.class).list();
    }

    private void finish(UUID id, String status, String category, String message) {
        jdbc.sql("""
                update index_job set status = :s, error_category = :c, error_message = :m, finished_at = now(),
                    updated_at = now() where id = :id""")
                .param("id", id).param("s", status).param("c", category).param("m", truncate(message)).update();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 500 ? s : s.substring(0, 500);
    }

    private static Job map(ResultSet rs, int i) throws SQLException {
        return new Job(rs.getObject("id", UUID.class), rs.getObject("repository_id", UUID.class),
                rs.getString("operation"), rs.getString("status"), rs.getString("commit_hash"), rs.getInt("attempts"),
                rs.getString("error_category"), rs.getString("error_message"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class), rs.getObject("files_parsed", Integer.class),
                rs.getObject("files_skipped", Integer.class), rs.getObject("symbols_extracted", Integer.class),
                rs.getString("parser_version"));
    }
}
