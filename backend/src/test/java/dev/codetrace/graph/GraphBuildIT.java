package dev.codetrace.graph;

import dev.codetrace.graph.engine.GraphBuilder;
import dev.codetrace.graph.repository.GraphIntegrityRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codetrace.jobs.queue.JobPublisher;
import dev.codetrace.jobs.queue.StreamBootstrap;
import dev.codetrace.support.GitFixtures;
import dev.codetrace.support.GitFixtures.Entry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Whole pipeline against real PostgreSQL and Redis: register -> sync -> index -> build graph -> published snapshot,
 * then idempotency, immutability, referential integrity and cascading cleanup.
 */
@Testcontainers
@ActiveProfiles({"api", "worker"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GraphBuildIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.0-pg17").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static Path root;
    static Path demoRepo;
    static Path smallRepo;
    static String demoCommit;
    static String smallCommit;

    static synchronized void createRepos() throws Exception {
        if (root != null) return;
        root = Files.createTempDirectory("codetrace-graph-it-").toRealPath();
        demoRepo = Files.createDirectories(root.resolve("demo-java"));
        smallRepo = Files.createDirectories(root.resolve("small"));
        demoCommit = GitFixtures.createDemoRepo(demoRepo);
        smallCommit = GitFixtures.createRepo(smallRepo, Map.of("src/p/A.java",
                Entry.file("package p; class A { void m() { n(); } void n() {} }")));
    }

    @BeforeAll
    static void beforeAll() throws Exception {
        createRepos();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        Path workspace = Files.createTempDirectory("codetrace-graph-it-ws-").toRealPath();
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("codetrace.ingestion.local-roots", () -> {
            try {
                createRepos();
                return root.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        r.add("codetrace.ingestion.workspace-dir", workspace::toString);
        r.add("codetrace.worker.sweep-ms", () -> "500");
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JobPublisher publisher;
    @Autowired GraphIntegrityRepository validator;
    @Autowired ObjectMapper json;

    static UUID demoRepoId;
    static UUID smallRepoId;
    static UUID snapshotId;
    static UUID buildJobId;

    // ---- helpers --------------------------------------------------------------------------------------------------

    private JsonNode get(String path) throws Exception {
        ResponseEntity<String> res = rest.getForEntity(path, String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).as(path + " -> " + res.getBody()).isTrue();
        return json.readTree(res.getBody());
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, h), String.class);
    }

    private JsonNode awaitJob(UUID jobId, String status) {
        return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
            JsonNode j = get("/api/v1/index-jobs/" + jobId);
            return status.equals(j.get("status").asText()) ? j : null;
        }, j -> j != null);
    }

    private UUID register(Path location) throws Exception {
        ResponseEntity<String> res = post("/api/v1/repositories",
                json.writeValueAsString(Map.of("sourceType", "LOCAL", "location", location.toString())));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = json.readTree(res.getBody());
        awaitJob(UUID.fromString(body.at("/syncJob/id").asText()), "SUCCEEDED");
        return UUID.fromString(body.at("/repository/id").asText());
    }

    private void index(UUID repoId) throws Exception {
        ResponseEntity<String> res = post("/api/v1/repositories/" + repoId + "/index-jobs", "{}");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitJob(UUID.fromString(json.readTree(res.getBody()).get("id").asText()), "SUCCEEDED");
    }

    private JsonNode awaitPublished(UUID snapshot) {
        return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
            JsonNode s = get("/api/v1/snapshots/" + snapshot);
            return "PUBLISHED".equals(s.get("status").asText()) ? s : null;
        }, s -> s != null);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ---- preconditions ------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void setUpRepositoriesAndIndexTheDemo() throws Exception {
        demoRepoId = register(demoRepo);
        smallRepoId = register(smallRepo);
        index(demoRepoId);
    }

    @Test
    @Order(2)
    void aRevisionThatWasNeverIndexedCannotBeBuilt() throws Exception {
        ResponseEntity<String> res = post("/api/v1/repositories/" + smallRepoId + "/graph-snapshots", "{}");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).contains("Index this revision first");
        assertThat(count("select count(*) from graph_snapshot where repository_id = ?", smallRepoId)).isZero();
    }

    @Test
    @Order(3)
    void invalidBuildRequestsAreRejected() {
        assertThat(post("/api/v1/repositories/" + demoRepoId + "/graph-snapshots", "{\"commitHash\":\"nope\"}")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/v1/repositories/" + demoRepoId + "/graph-snapshots", "{\"commitHash\":\"" + "d".repeat(40) + "\"}")
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/v1/repositories/" + UUID.randomUUID() + "/graph-snapshots", "{}").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- build and publish ------------------------------------------------------------------------------------------

    @Test
    @Order(4)
    void buildsAndPublishesTheDemoGraph() throws Exception {
        ResponseEntity<String> res = post("/api/v1/repositories/" + demoRepoId + "/graph-snapshots", "{}");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = json.readTree(res.getBody());
        snapshotId = UUID.fromString(body.at("/snapshot/id").asText());
        buildJobId = UUID.fromString(body.at("/job/id").asText());
        assertThat(body.at("/job/operation").asText()).isEqualTo("GRAPH_BUILD");
        assertThat(body.at("/snapshot/commitHash").asText()).isEqualTo(demoCommit);
        assertThat(body.at("/snapshot/builderVersion").asText()).isEqualTo(GraphBuilder.VERSION);

        awaitJob(buildJobId, "SUCCEEDED");
        JsonNode snap = awaitPublished(snapshotId);

        long nodes = count("select count(*) from code_node where snapshot_id = ?", snapshotId);
        long edges = count("select count(*) from dependency_edge where snapshot_id = ?", snapshotId);
        assertThat(snap.get("nodeCount").asLong()).isEqualTo(nodes).isPositive();
        assertThat(snap.get("edgeCount").asLong()).isEqualTo(edges).isPositive();
        assertThat(snap.get("resolvedEdgeCount").asLong() + snap.get("ambiguousEdgeCount").asLong()
                + snap.get("unresolvedEdgeCount").asLong()).isEqualTo(edges);
        assertThat(snap.get("publishedAt").isNull()).isFalse();
        assertThat(snap.get("failureReason").isNull()).isTrue();
    }

    @Test
    @Order(5)
    void everyNodeAndEdgeBelongsToTheRevisionAndSnapshot() {
        assertThat(count("select count(*) from code_node where snapshot_id = ? and (commit_hash <> ? or repository_id <> ?)",
                snapshotId, demoCommit, demoRepoId)).isZero();
        assertThat(count("select count(*) from dependency_edge where snapshot_id = ? and (commit_hash <> ? or repository_id <> ?)",
                snapshotId, demoCommit, demoRepoId)).isZero();
    }

    @Test
    @Order(6)
    void thePublishedGraphHasNoDanglingEdgesAndPassesTheValidator() {
        assertThat(count("""
                select count(*) from dependency_edge e where e.snapshot_id = ?
                  and (not exists (select 1 from code_node n where n.snapshot_id = e.snapshot_id and n.id = e.src_node_id)
                    or (e.dst_node_id is not null and not exists
                        (select 1 from code_node n where n.snapshot_id = e.snapshot_id and n.id = e.dst_node_id)))""",
                snapshotId)).isZero();

        long nodes = count("select count(*) from code_node where snapshot_id = ?", snapshotId);
        long edges = count("select count(*) from dependency_edge where snapshot_id = ?", snapshotId);
        assertThat(validator.validate(snapshotId, (int) nodes, (int) edges)).isEmpty();
    }

    @Test
    @Order(7)
    void everyEdgeCarriesProvenance() {
        assertThat(count("""
                select count(*) from dependency_edge where snapshot_id = ?
                  and (evidence_kind = '' or evidence_path = '' or extraction_method = '' or resolver_version <> ?)""",
                snapshotId, GraphBuilder.VERSION)).isZero();
        assertThat(count("select count(*) from code_node where snapshot_id = ? and (extraction_method = '' or parser_version = '')",
                snapshotId)).isZero();
    }

    @Test
    @Order(8)
    void theDemoGraphContainsTheExpectedEvidenceBackedEdges() {
        // Order IMPLEMENTS Priced: resolved through the same package
        assertThat(count("""
                select count(*) from dependency_edge e
                  join code_node s on s.snapshot_id = e.snapshot_id and s.id = e.src_node_id
                  join code_node d on d.snapshot_id = e.snapshot_id and d.id = e.dst_node_id
                where e.snapshot_id = ? and e.edge_type = 'IMPLEMENTS' and e.resolution_state = 'RESOLVED'
                  and s.qualified_name = 'com.acme.shop.Order' and d.qualified_name = 'com.acme.shop.Priced'
                  and e.evidence_kind = 'SAME_PACKAGE' and e.confidence = 1.0""", snapshotId)).isEqualTo(1);
        // addLine(String) -> addLine(String,int): a unique 2-argument overload
        assertThat(count("""
                select count(*) from dependency_edge e
                  join code_node s on s.snapshot_id = e.snapshot_id and s.id = e.src_node_id
                  join code_node d on d.snapshot_id = e.snapshot_id and d.id = e.dst_node_id
                where e.snapshot_id = ? and e.edge_type = 'CALLS' and e.resolution_state = 'RESOLVED'
                  and s.qualified_name = 'com.acme.shop.Order#addLine(String)'
                  and d.qualified_name = 'com.acme.shop.Order#addLine(String,int)'""", snapshotId)).isEqualTo(1);
        // addLine(sku) from addAll: two equal-arity overloads, so the edge is AMBIGUOUS (and grouped), not guessed
        assertThat(count("""
                select count(*) from dependency_edge e join code_node s on s.snapshot_id = e.snapshot_id and s.id = e.src_node_id
                where e.snapshot_id = ? and e.resolution_state = 'AMBIGUOUS' and e.edge_type = 'CALLS'
                  and s.qualified_name = 'com.acme.shop.Order#addAll(String...)'""", snapshotId)).isEqualTo(2);
        assertThat(count("""
                select count(distinct alternative_group) from dependency_edge
                where snapshot_id = ? and resolution_state = 'AMBIGUOUS' and edge_type = 'CALLS'""", snapshotId)).isPositive();
        // unknown territory stays unresolved with a reason and no target
        assertThat(count("""
                select count(*) from dependency_edge where snapshot_id = ? and resolution_state = 'UNRESOLVED'
                  and dst_node_id is null and reason <> '' and dst_name <> ''""", snapshotId)).isPositive();
        // nothing from ignored paths
        assertThat(count("select count(*) from code_node where snapshot_id = ? and (path like 'target/%' or path like 'node_modules/%')",
                snapshotId)).isZero();
    }

    // ---- idempotency and immutability ---------------------------------------------------------------------------------

    @Test
    @Order(9)
    void requestingTheSameBuildAgainReturnsTheSamePublishedSnapshot() throws Exception {
        long nodesBefore = count("select count(*) from code_node where snapshot_id = ?", snapshotId);

        ResponseEntity<String> res = post("/api/v1/repositories/" + demoRepoId + "/graph-snapshots",
                "{\"commitHash\":\"" + demoCommit + "\"}");

        JsonNode body = json.readTree(res.getBody());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(body.at("/snapshot/id").asText()).isEqualTo(snapshotId.toString());
        assertThat(body.at("/snapshot/status").asText()).isEqualTo("PUBLISHED");
        assertThat(body.at("/job/id").asText()).isEqualTo(buildJobId.toString());
        assertThat(count("select count(*) from graph_snapshot where repository_id = ?", demoRepoId)).isEqualTo(1);
        assertThat(count("select count(*) from index_job where repository_id = ? and operation = 'GRAPH_BUILD'", demoRepoId))
                .isEqualTo(1);
        assertThat(count("select count(*) from code_node where snapshot_id = ?", snapshotId)).isEqualTo(nodesBefore);
    }

    @Test
    @Order(10)
    void redeliveredBuildMessagesDoNothing() {
        long nodes = count("select count(*) from code_node where snapshot_id = ?", snapshotId);
        long edges = count("select count(*) from dependency_edge where snapshot_id = ?", snapshotId);
        int attempts = jdbc.queryForObject("select attempts from index_job where id = ?", Integer.class, buildJobId);

        publisher.publish(buildJobId);
        String last = publisher.publish(buildJobId);

        await().atMost(Duration.ofSeconds(30)).until(() -> redis.opsForStream().groups(StreamBootstrap.STREAM).stream()
                .anyMatch(g -> g.groupName().equals(StreamBootstrap.GROUP) && g.lastDeliveredId().equals(last)
                        && g.pendingCount() == 0));
        assertThat(count("select count(*) from code_node where snapshot_id = ?", snapshotId)).isEqualTo(nodes);
        assertThat(count("select count(*) from dependency_edge where snapshot_id = ?", snapshotId)).isEqualTo(edges);
        assertThat(jdbc.queryForObject("select attempts from index_job where id = ?", Integer.class, buildJobId))
                .isEqualTo(attempts);
        assertThat(jdbc.queryForObject("select status from graph_snapshot where id = ?", String.class, snapshotId))
                .isEqualTo("PUBLISHED");
    }

    @Test
    @Order(11)
    void aPublishedSnapshotRejectsDirectModification() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into code_node (id, snapshot_id, repository_id, commit_hash, node_type, qualified_name, simple_name,
                    extraction_method, parser_version) values (?, ?, ?, ?, 'TYPE', 'x.Y', 'Y', 'm', 'p')""",
                UUID.randomUUID(), snapshotId, demoRepoId, demoCommit)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from dependency_edge where snapshot_id = ?", snapshotId))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("update graph_snapshot set status = 'FAILED' where id = ?", snapshotId))
                .hasMessageContaining("immutable");
    }

    @Test
    @Order(12)
    void unknownSnapshotsAre404() {
        assertThat(rest.getForEntity("/api/v1/snapshots/" + UUID.randomUUID(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/v1/repositories/" + UUID.randomUUID() + "/snapshots", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(13)
    void snapshotsAreListedPerRepository() throws Exception {
        JsonNode list = get("/api/v1/repositories/" + demoRepoId + "/snapshots");

        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asText()).isEqualTo(snapshotId.toString());
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------------------

    @Test
    @Order(14)
    void deletingARepositoryRemovesItsPublishedGraph() throws Exception {
        index(smallRepoId);
        ResponseEntity<String> res = post("/api/v1/repositories/" + smallRepoId + "/graph-snapshots", "{}");
        UUID small = UUID.fromString(json.readTree(res.getBody()).at("/snapshot/id").asText());
        awaitPublished(small);
        assertThat(count("select count(*) from code_node where snapshot_id = ?", small)).isPositive();

        jdbc.update("delete from repository where id = ?", smallRepoId);

        assertThat(count("select count(*) from graph_snapshot where id = ?", small)).isZero();
        assertThat(count("select count(*) from code_node where snapshot_id = ?", small)).isZero();
        assertThat(count("select count(*) from dependency_edge where snapshot_id = ?", small)).isZero();
        // the other repository's graph is untouched
        assertThat(count("select count(*) from code_node where snapshot_id = ?", snapshotId)).isPositive();
    }
}
