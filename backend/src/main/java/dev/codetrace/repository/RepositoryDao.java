package dev.codetrace.repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositoryDao {
    public record Repo(UUID id, SourceType sourceType, String location, String displayName, String syncStatus,
                       String defaultBranch, String headCommit, OffsetDateTime createdAt) {
    }

    public record Revision(UUID id, String commitHash, String summary, OffsetDateTime authorTime) {
    }

    private final JdbcClient jdbc;

    public RepositoryDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS =
            "id, source_type, location, display_name, sync_status, default_branch, head_commit, created_at";

    public Repo insert(UUID id, SourceType type, String location, String displayName) {
        jdbc.sql("insert into repository (id, source_type, location, display_name) values (:id, :t, :l, :d)")
                .param("id", id).param("t", type.name()).param("l", location).param("d", displayName).update();
        return find(id).orElseThrow();
    }

    public Optional<Repo> find(UUID id) {
        return jdbc.sql("select " + COLS + " from repository where id = :id").param("id", id)
                .query(RepositoryDao::mapRepo).optional();
    }

    public Optional<Repo> findBySource(SourceType type, String location) {
        return jdbc.sql("select " + COLS + " from repository where source_type = :t and location = :l")
                .param("t", type.name()).param("l", location).query(RepositoryDao::mapRepo).optional();
    }

    public List<Repo> list(int limit) {
        return jdbc.sql("select " + COLS + " from repository order by created_at desc limit :n")
                .param("n", limit).query(RepositoryDao::mapRepo).list();
    }

    public void markSynced(UUID id, String defaultBranch, String headCommit) {
        jdbc.sql("""
                update repository set sync_status = 'SYNCED', default_branch = :b, head_commit = :h, updated_at = now()
                where id = :id""")
                .param("id", id).param("b", defaultBranch, Types.VARCHAR).param("h", headCommit).update();
    }

    public void markSyncFailed(UUID id) {
        jdbc.sql("update repository set sync_status = 'FAILED', updated_at = now() where id = :id")
                .param("id", id).update();
    }

    public void markSyncPending(UUID id) {
        jdbc.sql("update repository set sync_status = 'PENDING', updated_at = now() where id = :id and sync_status = 'FAILED'")
                .param("id", id).update();
    }

    public void upsertRevision(UUID repositoryId, String commitHash, String summary, OffsetDateTime authorTime) {
        jdbc.sql("""
                insert into revision (id, repository_id, commit_hash, summary, author_time)
                values (:id, :r, :h, :s, :t) on conflict (repository_id, commit_hash) do nothing""")
                .param("id", UUID.randomUUID()).param("r", repositoryId).param("h", commitHash)
                .param("s", summary).param("t", authorTime, Types.TIMESTAMP_WITH_TIMEZONE).update();
    }

    public List<Revision> listRevisions(UUID repositoryId, int limit) {
        return jdbc.sql("""
                select id, commit_hash, summary, author_time from revision where repository_id = :r
                order by author_time desc nulls last, discovered_at desc limit :n""")
                .param("r", repositoryId).param("n", limit)
                .query((rs, i) -> new Revision(rs.getObject("id", UUID.class), rs.getString("commit_hash"),
                        rs.getString("summary"), rs.getObject("author_time", OffsetDateTime.class)))
                .list();
    }

    public boolean revisionExists(UUID repositoryId, String commitHash) {
        return jdbc.sql("select count(*) from revision where repository_id = :r and commit_hash = :h")
                .param("r", repositoryId).param("h", commitHash).query(Long.class).single() > 0;
    }

    private static Repo mapRepo(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Repo(rs.getObject("id", UUID.class), SourceType.valueOf(rs.getString("source_type")),
                rs.getString("location"), rs.getString("display_name"), rs.getString("sync_status"),
                rs.getString("default_branch"), rs.getString("head_commit"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
