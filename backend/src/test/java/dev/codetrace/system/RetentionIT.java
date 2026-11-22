package dev.codetrace.system;

import static org.assertj.core.api.Assertions.assertThat;

import dev.codetrace.graph.engine.GraphBuilder;
import dev.codetrace.graph.model.GraphModel.Result;
import dev.codetrace.graph.model.Snapshot;
import dev.codetrace.graph.repository.GraphSnapshotRepository;
import dev.codetrace.graph.repository.GraphWriteRepository;
import dev.codetrace.impact.service.ImpactAnalyzer;
import dev.codetrace.source.model.SourceType;
import dev.codetrace.source.repository.SourceRepositoryRepository;
import dev.codetrace.system.service.RetentionService;
import dev.codetrace.system.service.RetentionService.Pruned;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** One clean-up pass removes superseded and old failed results and keeps everything still in use. */
@Testcontainers
@ActiveProfiles("worker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RetentionIT {
    private static final String A = "a".repeat(40), B = "b".repeat(40), C = "c".repeat(40), D = "d".repeat(40);
    private static final String OLD_BUILDER = "graph-builder-1";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.0-pg17").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("codetrace.retention.cron", () -> "-"); // only the explicit run below
    }

    @Autowired RetentionService retention;
    @Autowired SourceRepositoryRepository repos;
    @Autowired GraphSnapshotRepository snapshots;
    @Autowired GraphWriteRepository writer;
    @Autowired JdbcTemplate jdbc;

    private UUID repo;

    private UUID job(String operation, String status, String commit, Integer extractionVersion, int daysAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into index_job (id, repository_id, operation, status, commit_hash, idempotency_key, extraction_version,
                                       finished_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, now() - make_interval(days => ?), now() - make_interval(days => ?),
                        now() - make_interval(days => ?))""",
                id, repo, operation, status, commit, "test-" + id, extractionVersion, daysAgo, daysAgo, daysAgo);
        return id;
    }

    private UUID published(String commit, String builder, UUID sourceJob) {
        Snapshot s = snapshots.createOrGet(repo, commit, "p1", builder, sourceJob).snapshot();
        assertThat(snapshots.begin(s.id())).isTrue();
        writer.write(s, new Result(List.of(), List.of(), new TreeMap<>()));
        assertThat(snapshots.publish(s.id())).isTrue();
        return s.id();
    }

    private UUID report(String algorithm, String status, UUID base, String baseCommit, UUID head, String headCommit,
                        int depth, int daysAgo) {
        UUID id = UUID.randomUUID();
        UUID job = job("IMPACT", status.equals("COMPLETED") ? "SUCCEEDED" : "FAILED", null, null, daysAgo);
        boolean done = status.equals("COMPLETED");
        jdbc.update("""
                insert into impact_report (id, repository_id, base_commit, head_commit, base_snapshot_id, head_snapshot_id,
                    algorithm_version, depth, job_id, status, risk_score, risk_level, risk_factors, affected_count,
                    completed_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, now() - make_interval(days => ?))""",
                id, repo, baseCommit, headCommit, base, head, algorithm, depth, job, status, done ? 0 : null,
                done ? "LOW" : null, done ? "[]" : null, done ? 0 : null, done ? java.sql.Timestamp.from(java.time.Instant.now()) : null,
                daysAgo);
        return id;
    }

    private boolean exists(String table, UUID id) {
        return jdbc.queryForObject("select count(*) from " + table + " where id = ?", Long.class, id) == 1;
    }

    @Test
    void removesSupersededAndOldFailedResultsAndKeepsWhatIsStillUsed() {
        repo = repos.insert(UUID.randomUUID(), SourceType.GIT_URL, "https://github.com/o/" + UUID.randomUUID(), "o/r").id();
        String current = GraphBuilder.VERSION;
        UUID indexB = job("INDEX", "SUCCEEDED", B, 3, 40);          // stale, but feeds a kept snapshot
        UUID staleIndex = job("INDEX", "SUCCEEDED", D, 3, 40);      // stale and unused: removed
        UUID recentStale = job("INDEX", "SUCCEEDED", A, 3, 1);      // stale but recent: kept
        UUID oldFailedSync = job("SYNC", "FAILED", null, null, 10); // removed
        UUID newFailedSync = job("SYNC", "FAILED", null, null, 1);  // kept

        UUID aOld = published(A, OLD_BUILDER, null);   // superseded by aCur: removed
        UUID aCur = published(A, current, null);
        UUID bOld = published(B, OLD_BUILDER, indexB); // no current graph for B: kept
        UUID cOld = published(C, OLD_BUILDER, null);   // superseded, freed once its old report goes: removed
        UUID cCur = published(C, current, null);
        Snapshot failed = snapshots.createOrGet(repo, D, "p1", current, null).snapshot();
        snapshots.markFailed(failed.id(), "boom");
        jdbc.update("update graph_snapshot set created_at = now() - interval '10 days' where id = ?", failed.id());

        UUID oldReport = report("impact-1", "COMPLETED", bOld, B, cOld, C, 4, 3);                        // removed
        UUID newReport = report(ImpactAnalyzer.ALGORITHM_VERSION, "COMPLETED", bOld, B, cCur, C, 4, 1);  // kept
        UUID oldFailedReport = report("impact-1", "FAILED", bOld, B, cCur, C, 5, 10);                    // removed
        UUID newFailedReport = report("impact-1", "FAILED", bOld, B, cCur, C, 6, 1);                     // kept

        Pruned p = retention.run();

        assertThat(p).isEqualTo(new Pruned(1, 1, 2, 1, 2, 1, 1));
        assertThat(exists("graph_snapshot", aOld)).isFalse();
        assertThat(exists("graph_snapshot", cOld)).isFalse();
        assertThat(exists("graph_snapshot", failed.id())).isFalse();
        assertThat(List.of(aCur, bOld, cCur)).allMatch(id -> exists("graph_snapshot", id));
        assertThat(exists("impact_report", oldReport)).isFalse();
        assertThat(exists("impact_report", oldFailedReport)).isFalse();
        assertThat(List.of(newReport, newFailedReport)).allMatch(id -> exists("impact_report", id));
        assertThat(exists("index_job", staleIndex)).isFalse();
        assertThat(exists("index_job", oldFailedSync)).isFalse();
        assertThat(List.of(indexB, recentStale, newFailedSync)).allMatch(id -> exists("index_job", id));

        assertThat(retention.run().total()).as("a second pass finds nothing").isZero();
    }
}
