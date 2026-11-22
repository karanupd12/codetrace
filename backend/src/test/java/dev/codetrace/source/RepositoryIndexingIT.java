package dev.codetrace.source;

import dev.codetrace.source.repository.SourceRepositoryRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codetrace.jobs.queue.JobPublisher;
import dev.codetrace.jobs.queue.StreamBootstrap;
import dev.codetrace.support.GitFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.stream.StreamInfo;
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
 * End to end: API + worker (both profiles in one context) against real PostgreSQL and Redis.
 * Register a local repo -> worker syncs -> index the pinned revision -> query symbols/diagnostics, then prove
 * idempotency, redelivery safety and failure handling.
 */
@Testcontainers
@ActiveProfiles({"api", "worker"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RepositoryIndexingIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.0-pg17").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static Path root;
    static Path demoRepo;
    static String commit;

    @BeforeAll
    static synchronized void createRepo() throws Exception {
        if (root != null) return; // idempotent: the property supplier may run before or after @BeforeAll
        root = Files.createTempDirectory("codetrace-it-").toRealPath();
        demoRepo = Files.createDirectories(root.resolve("demo-java"));
        commit = GitFixtures.createDemoRepo(demoRepo);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path workspace = Files.createTempDirectory("codetrace-it-ws-").toRealPath();
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("codetrace.ingestion.local-roots", () -> {
            try {
                if (root == null) createRepo();
                return root.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        r.add("codetrace.ingestion.workspace-dir", workspace::toString);
        r.add("codetrace.ingestion.retry-delay-seconds", () -> "1");
        r.add("codetrace.worker.sweep-ms", () -> "500");
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JobPublisher publisher;
    @Autowired SourceRepositoryRepository repoDao;
    @Autowired ObjectMapper json;

    static UUID repositoryId;
    static UUID indexJobId;

    // ------------------------------------------------------------------------------------------------------

    private JsonNode get(String path, Object... uriVars) throws Exception {
        ResponseEntity<String> res = rest.getForEntity(path, String.class, uriVars);
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

    private String pathsOf(JsonNode symbolsPage) {
        StringBuilder sb = new StringBuilder();
        symbolsPage.get("items").forEach(i -> sb.append(i.get("path").asText()).append('\n'));
        return sb.toString();
    }

    // ---- registration + sync --------------------------------------------------------------------------------

    @Test
    @Order(1)
    void registersLocalRepositoryAndSyncsRevisions() throws Exception {
        ResponseEntity<String> res = post("/api/v1/repositories",
                json.writeValueAsString(java.util.Map.of("sourceType", "LOCAL", "location", demoRepo.toString())));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = json.readTree(res.getBody());
        repositoryId = UUID.fromString(body.at("/repository/id").asText());
        assertThat(body.at("/repository/sourceType").asText()).isEqualTo("LOCAL");
        assertThat(body.at("/repository/displayName").asText()).isEqualTo("demo-java");
        assertThat(body.at("/syncJob/operation").asText()).isEqualTo("SYNC");
        // API never reveals server filesystem paths
        assertThat(res.getBody()).doesNotContain(root.toString()).doesNotContain("codetrace-it-");
        assertThat(body.at("/repository/originUrl").isNull()).isTrue();

        UUID syncJob = UUID.fromString(body.at("/syncJob/id").asText());
        awaitJob(syncJob, "SUCCEEDED");

        JsonNode repo = get("/api/v1/repositories/" + repositoryId);
        assertThat(repo.get("syncStatus").asText()).isEqualTo("SYNCED");
        assertThat(repo.get("headCommit").asText()).isEqualTo(commit);
        assertThat(repo.get("defaultBranch").asText()).isEqualTo("main");

        JsonNode revisions = get("/api/v1/repositories/" + repositoryId + "/revisions");
        assertThat(revisions).hasSize(1);
        assertThat(revisions.get(0).get("commitHash").asText()).isEqualTo(commit);
    }

    @Test
    @Order(2)
    void registeringTheSameSourceAgainIsIdempotent() throws Exception {
        ResponseEntity<String> res = post("/api/v1/repositories",
                json.writeValueAsString(java.util.Map.of("sourceType", "LOCAL", "location", demoRepo.toString())));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(res.getBody()).at("/repository/id").asText()).isEqualTo(repositoryId.toString());
        assertThat(jdbc.queryForObject("select count(*) from repository", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from index_job where operation = 'SYNC'", Integer.class))
                .isEqualTo(1);
    }

    // ---- indexing -------------------------------------------------------------------------------------------

    @Test
    @Order(3)
    void indexesThePinnedRevision() throws Exception {
        ResponseEntity<String> res = post("/api/v1/repositories/" + repositoryId + "/index-jobs",
                json.writeValueAsString(java.util.Map.of("commitHash", commit)));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        indexJobId = UUID.fromString(json.readTree(res.getBody()).get("id").asText());

        JsonNode job = awaitJob(indexJobId, "SUCCEEDED");
        assertThat(job.get("commitHash").asText()).isEqualTo(commit);
        assertThat(job.get("filesParsed").asInt()).isBetween(3, 4); // Order, OrderService, Priced (+ Broken if recovered)
        assertThat(job.get("filesSkipped").asInt()).isEqualTo(2);  // target/ + node_modules/ files
        assertThat(job.get("symbolsExtracted").asInt()).isPositive();
        assertThat(job.get("parserVersion").asText()).startsWith("javaparser-core-");
        assertThat(job.get("attempts").asInt()).isEqualTo(1);
    }

    @Test
    @Order(4)
    void symbolsCoverOverloadsNestedTypesAndAreBounded() throws Exception {
        String base = "/api/v1/index-jobs/" + indexJobId + "/symbols";

        JsonNode overloads = get(base + "?kind=METHOD&q={q}", "Order#addLine"); // '#' must be encoded by the client
        assertThat(overloads.get("total").asInt()).isEqualTo(3);
        List<String> sigs = overloads.get("items").findValuesAsText("signature");
        assertThat(sigs).containsExactlyInAnyOrder("addLine(String)", "addLine(String,int)", "addLine(Line)");

        JsonNode types = get(base + "?kind=TYPE&q=order");
        assertThat(types.get("items").findValuesAsText("qualifiedName")).contains(
                "com.acme.shop.Order", "com.acme.shop.Order.Line", "com.acme.shop.Order.Line.Detail",
                "com.acme.shop.Order.Status", "com.acme.shop.OrderService", "com.acme.shop.OrderService.Receipt");

        // every symbol identifies its revision and parser provenance
        JsonNode any = get(base + "?limit=1").get("items").get(0);
        assertThat(any.get("commitHash").asText()).isEqualTo(commit);
        assertThat(any.get("extractionMethod").asText()).isNotBlank();
        assertThat(any.get("parserVersion").asText()).isNotBlank();

        // result sizes are capped
        assertThat(get(base + "?limit=100000").get("limit").asInt()).isEqualTo(200);
        assertThat(get(base + "?limit=0").get("limit").asInt()).isEqualTo(1);
    }

    @Test
    @Order(5)
    void ignoredPathsProduceNoSymbolsAndMalformedFilesProduceDiagnostics() throws Exception {
        String base = "/api/v1/index-jobs/" + indexJobId;
        String allPaths = pathsOf(get(base + "/symbols?limit=200"));
        assertThat(allPaths).doesNotContain("target/").doesNotContain("node_modules/");
        assertThat(jdbc.queryForObject(
                "select count(*) from indexed_file where job_id = ? and (path like 'target/%' or path like 'node_modules/%')",
                Integer.class, indexJobId)).isZero();

        JsonNode diags = get(base + "/diagnostics");
        assertThat(diags.get("total").asInt()).isPositive();
        assertThat(diags.get("items").findValuesAsText("path")).allMatch(p -> p.endsWith("Broken.java"));
        assertThat(diags.get("items").findValuesAsText("code")).contains("SYNTAX_ERROR");

        // the malformed file did not stop the other files from being indexed
        assertThat(jdbc.queryForObject(
                "select count(*) from code_symbol where job_id = ? and path like '%Order.java'", Integer.class,
                indexJobId)).isPositive();
        assertThat(jdbc.queryForObject("select status from indexed_file where job_id = ? and path like '%Broken.java'",
                String.class, indexJobId)).isIn("PARTIAL", "FAILED");
    }

    @Test
    @Order(6)
    void importsArePersistedWithStaticAndWildcardFlags() {
        assertThat(jdbc.queryForObject("""
                select count(*) from code_import where job_id = ? and path like '%Order.java'
                  and imported_name = 'java.util.Map.*' and is_wildcard""", Integer.class, indexJobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from code_import where job_id = ? and imported_name = 'java.util.Objects.requireNonNull'
                  and is_static""", Integer.class, indexJobId)).isEqualTo(1);
    }

    // ---- idempotency / redelivery ---------------------------------------------------------------------------

    @Test
    @Order(6)
    void referencesAreStoredAsUnresolvedFactsAndTheJobRecordsItsExtractionVersion() {
        assertThat(jdbc.queryForObject("select extraction_version from index_job where id = ?", Integer.class,
                indexJobId)).isEqualTo(3);
        // Order.addLine(String) calls the overload addLine(sku, 1) with no receiver: a fact, not a resolved edge
        assertThat(jdbc.queryForObject("""
                select count(*) from code_reference where job_id = ? and kind = 'CALLS' and name = 'addLine'
                  and arg_count = 2 and receiver_kind = 'NONE'
                  and owner_qualified_name = 'com.acme.shop.Order#addLine(String)'""", Integer.class, indexJobId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from code_reference where job_id = ? and kind = 'IMPLEMENTS' and name = 'Priced'
                  and owner_qualified_name = 'com.acme.shop.Order'""", Integer.class, indexJobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from code_reference where job_id = ?", Integer.class,
                indexJobId)).isPositive();
    }

    @Test
    @Order(7)
    void requestingTheSameIndexJobAgainReturnsTheSameFinishedJob() throws Exception {
        int before = jdbc.queryForObject("select count(*) from code_symbol where job_id = ?", Integer.class, indexJobId);

        ResponseEntity<String> res = post("/api/v1/repositories/" + repositoryId + "/index-jobs", "{}");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode job = json.readTree(res.getBody());
        assertThat(job.get("id").asText()).isEqualTo(indexJobId.toString()); // HEAD default == pinned commit
        assertThat(job.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select count(*) from index_job where operation = 'INDEX'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from code_symbol where job_id = ?", Integer.class, indexJobId))
                .isEqualTo(before);
    }

    @Test
    @Order(8)
    void redeliveredMessagesDoNotReRunOrDuplicateResults() {
        int symbols = jdbc.queryForObject("select count(*) from code_symbol where job_id = ?", Integer.class, indexJobId);
        int diagnostics = jdbc.queryForObject("select count(*) from parse_diagnostic where job_id = ?", Integer.class, indexJobId);
        int attempts = jdbc.queryForObject("select attempts from index_job where id = ?", Integer.class, indexJobId);

        publisher.publish(indexJobId);
        String last = publisher.publish(indexJobId);

        await().atMost(Duration.ofSeconds(30)).until(() -> consumedAndAcked(last));
        assertThat(jdbc.queryForObject("select count(*) from code_symbol where job_id = ?", Integer.class, indexJobId))
                .isEqualTo(symbols);
        assertThat(jdbc.queryForObject("select count(*) from parse_diagnostic where job_id = ?", Integer.class, indexJobId))
                .isEqualTo(diagnostics);
        assertThat(jdbc.queryForObject("select attempts from index_job where id = ?", Integer.class, indexJobId))
                .isEqualTo(attempts);
        assertThat(jdbc.queryForObject("select status from index_job where id = ?", String.class, indexJobId))
                .isEqualTo("SUCCEEDED");
    }

    private boolean consumedAndAcked(String lastId) {
        StreamInfo.XInfoGroups groups = redis.opsForStream().groups(StreamBootstrap.STREAM);
        return groups.stream().anyMatch(g -> g.groupName().equals(StreamBootstrap.GROUP)
                && g.lastDeliveredId().equals(lastId) && g.pendingCount() == 0);
    }

    // ---- failures -------------------------------------------------------------------------------------------

    @Test
    @Order(9)
    void unknownRevisionsAreRejectedAtTheApi() throws Exception {
        ResponseEntity<String> res = post("/api/v1/repositories/" + repositoryId + "/index-jobs",
                "{\"commitHash\":\"" + "b".repeat(40) + "\"}");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> bad = post("/api/v1/repositories/" + repositoryId + "/index-jobs",
                "{\"commitHash\":\"not-a-hash\"}");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Order(10)
    void aRevisionMissingFromGitFailsPermanentlyWithoutRetry() throws Exception {
        String ghost = "c".repeat(40);
        repoDao.upsertRevision(repositoryId, ghost, "ghost", null); // known to the DB but absent from git

        JsonNode queued = json.readTree(post("/api/v1/repositories/" + repositoryId + "/index-jobs",
                "{\"commitHash\":\"" + ghost + "\"}").getBody());
        UUID id = UUID.fromString(queued.get("id").asText());

        JsonNode failed = awaitJob(id, "FAILED");
        assertThat(failed.get("errorCategory").asText()).isEqualTo("REVISION_NOT_FOUND");
        assertThat(failed.get("attempts").asInt()).isEqualTo(1);       // permanent: not retried
        assertThat(failed.get("errorMessage").asText()).doesNotContain(root.toString());
        assertThat(jdbc.queryForObject("select count(*) from code_symbol where job_id = ?", Integer.class, id)).isZero();
    }

    @Test
    @Order(11)
    void invalidSourcesAreRejectedWithProblemDetails() throws Exception {
        for (String body : List.of(
                "{\"sourceType\":\"GIT_URL\",\"location\":\"http://github.com/o/r\"}",
                "{\"sourceType\":\"GIT_URL\",\"location\":\"https://user:pw@github.com/o/r\"}",
                "{\"sourceType\":\"GIT_URL\",\"location\":\"file:///etc/passwd\"}",
                "{\"sourceType\":\"GIT_URL\",\"location\":\"https://evil.example.com/o/r\"}",
                "{\"sourceType\":\"LOCAL\",\"location\":\"" + root.getParent().toString().replace("\\", "\\\\") + "\"}",
                "{\"sourceType\":\"LOCAL\",\"location\":\"" + root.toString().replace("\\", "\\\\") + "\"}",
                "{\"sourceType\":\"NOPE\",\"location\":\"x\"}",
                "{\"sourceType\":\"LOCAL\"}",
                "{}")) {
            ResponseEntity<String> res = post("/api/v1/repositories", body);
            assertThat(res.getStatusCode()).as(body).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody()).as(body).contains("\"status\":400").doesNotContain("Exception");
        }
        assertThat(jdbc.queryForObject("select count(*) from repository", Integer.class)).isEqualTo(1);
    }

    @Test
    @Order(12)
    void unknownIdsReturn404() {
        assertThat(rest.getForEntity("/api/v1/repositories/" + UUID.randomUUID(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/v1/index-jobs/" + UUID.randomUUID(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/v1/index-jobs/not-a-uuid", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Order(14)
    void aFinishedIndexFromAnOlderExtractorIsReExtractedOnRequest() throws Exception {
        jdbc.update("update index_job set extraction_version = null where id = ?", indexJobId); // as left by earlier releases
        int refsBefore = jdbc.queryForObject("select count(*) from code_reference where job_id = ?", Integer.class, indexJobId);

        ResponseEntity<String> res = post("/api/v1/repositories/" + repositoryId + "/index-jobs", "{}");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(json.readTree(res.getBody()).get("id").asText()).isEqualTo(indexJobId.toString()); // same logical job
        awaitJob(indexJobId, "SUCCEEDED");
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "select extraction_version from index_job where id = ?", Integer.class, indexJobId) != null);
        assertThat(jdbc.queryForObject("select extraction_version from index_job where id = ?", Integer.class, indexJobId))
                .isEqualTo(3);
        // replaced, not duplicated
        assertThat(jdbc.queryForObject("select count(*) from code_reference where job_id = ?", Integer.class, indexJobId))
                .isEqualTo(refsBefore);
    }

    @Test
    @Order(13)
    void symbolQueriesRejectSyncJobsAndBadKinds() throws Exception {
        UUID sync = UUID.fromString(jdbc.queryForObject(
                "select id::text from index_job where operation = 'SYNC'", String.class));
        assertThat(rest.getForEntity("/api/v1/index-jobs/" + sync + "/symbols", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/v1/index-jobs/" + indexJobId + "/symbols?kind=BANANA", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // LIKE metacharacters in q are literal, not wildcards
        assertThat(get("/api/v1/index-jobs/" + indexJobId + "/symbols?q=%25%25%25").get("total").asInt()).isZero();
    }
}
