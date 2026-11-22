package dev.codetrace.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codetrace.graph.engine.GraphBuilder;
import dev.codetrace.graph.model.GraphModel.Limits;
import dev.codetrace.graph.model.GraphModel.Result;
import dev.codetrace.graph.repository.GraphInputRepository;
import dev.codetrace.ingestion.indexer.JavaIndexer;
import dev.codetrace.ingestion.service.IndexResultService;
import dev.codetrace.support.GitFixtures;
import dev.codetrace.support.GitFixtures.Entry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * Indexing a second commit copies the facts of unchanged files from the first index, and the result is equivalent to
 * indexing that commit from scratch: the same stored files and the same dependency graph.
 */
@Testcontainers
@ActiveProfiles({"api", "worker"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IncrementalIndexingIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.0-pg17").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static Path root;
    static Path repoDir;
    static String first;
    static String second;

    static synchronized void createRepo() throws Exception {
        if (root != null) return;
        root = Files.createTempDirectory("codetrace-incremental-it-").toRealPath();
        repoDir = Files.createDirectories(root.resolve("shop"));
        first = GitFixtures.createRepo(repoDir, Map.of(
                "src/p/A.java", Entry.file("package p; import java.util.List; public class A { List<B> all; B b = new B(); @org.springframework.web.bind.annotation.GetMapping(\"/run\") void run() { b.go(1); b.go(\"x\"); } }"),
                "src/p/B.java", Entry.file("package p; public class B { void go(int n) {} void go(String s) {} }"),
                "src/p/C.java", Entry.file("package p; public class C extends A { void more() { run(); } }"),
                "src/p/Broken.java", Entry.file("package p; public class Broken {"),
                "test/p/ATest.java", Entry.file("package p; class ATest { void t() { new A().run(); } }")));
        try (Repository repo = open()) {
            second = GitFixtures.commit(repo, Map.of(
                    "src/p/A.java", Entry.file("package p; import java.util.List; public class A { List<B> all; B b = new B(); @org.springframework.web.bind.annotation.GetMapping(\"/run\") void run() { b.go(1); b.go(\"x\"); } }"),
                    "src/p/B.java", Entry.file("package p; public class B { void go(int n) {} void go(String s) {} void stop() {} }"),
                    "src/q/C.java", Entry.file("package p; public class C extends A { void more() { run(); } }"),
                    "src/p/Broken.java", Entry.file("package p; public class Broken {"),
                    "src/p/D.java", Entry.file("package p; public class D { void d(B b) { b.stop(); } }")), first, "second");
        }
    }

    static Repository open() throws Exception {
        return new FileRepositoryBuilder().setGitDir(repoDir.resolve(".git").toFile()).setMustExist(true).build();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        Path workspace = Files.createTempDirectory("codetrace-incremental-it-ws-").toRealPath();
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("codetrace.ingestion.local-roots", () -> {
            try {
                createRepo();
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
    @Autowired ObjectMapper json;
    @Autowired JavaIndexer indexer;
    @Autowired IndexResultService results;
    @Autowired GraphInputRepository inputs;

    private JsonNode get(String path) throws Exception {
        ResponseEntity<String> res = rest.getForEntity(path, String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).as(path + " -> " + res.getBody()).isTrue();
        return json.readTree(res.getBody());
    }

    private JsonNode post(String path, Object body) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = rest.postForEntity(path, new HttpEntity<>(json.writeValueAsString(body), h), String.class);
        assertThat(res.getStatusCode()).as(path + " -> " + res.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        return json.readTree(res.getBody());
    }

    private JsonNode awaitSucceeded(String jobId) {
        return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
            JsonNode j = get("/api/v1/index-jobs/" + jobId);
            return "SUCCEEDED".equals(j.get("status").asText()) ? j : null;
        }, j -> j != null);
    }

    private JsonNode index(UUID repoId, String commit) throws Exception {
        return awaitSucceeded(post("/api/v1/repositories/" + repoId + "/index-jobs", Map.of("commitHash", commit))
                .get("id").asText());
    }

    private List<String> rows(String sql, UUID jobId) {
        return jdbc.queryForList(sql, String.class, jobId).stream().sorted().toList();
    }

    private static List<String> sorted(List<?> items) {
        return items.stream().map(Object::toString).sorted(Comparator.naturalOrder()).toList();
    }

    @Test
    void anIncrementalIndexMatchesAFullIndexOfTheSameCommit() throws Exception {
        JsonNode registered = post("/api/v1/repositories", Map.of("sourceType", "LOCAL", "location", repoDir.toString()));
        awaitSucceeded(registered.at("/syncJob/id").asText());
        UUID repoId = UUID.fromString(registered.at("/repository/id").asText());

        JsonNode base = index(repoId, first);
        JsonNode incremental = index(repoId, second);

        assertThat(base.get("filesReused").asInt()).isZero();
        assertThat(incremental.get("baseJobId").asText()).isEqualTo(base.get("id").asText());
        assertThat(incremental.get("filesReused").asInt()).as("A, Broken and the renamed C").isEqualTo(3);

        // A full index of the same commit, stored under its own job, for comparison.
        UUID fullJob = UUID.randomUUID();
        jdbc.update("""
                insert into index_job (id, repository_id, operation, status, commit_hash, idempotency_key)
                values (?, ?, 'INDEX', 'RUNNING', ?, ?)""", fullJob, repoId, second, "full-" + fullJob);
        try (Repository repo = open()) {
            results.store(fullJob, repoId, second, indexer.index(repo, second));
        }
        UUID incrementalJob = UUID.fromString(incremental.get("id").asText());

        assertThat(incremental.get("filesParsed").asInt())
                .isEqualTo(jdbc.queryForObject("select files_parsed from index_job where id = ?", Integer.class, fullJob));
        assertThat(incremental.get("symbolsExtracted").asInt())
                .isEqualTo(jdbc.queryForObject("select symbols_extracted from index_job where id = ?", Integer.class, fullJob));
        for (String sql : List.of(
                "select concat_ws('|', path, package_name, status, skip_reason, size_bytes, blob_id) from indexed_file where job_id = ?",
                "select concat_ws('|', path, kind, subtype, qualified_name, signature, parent_qualified_name, modifiers, start_line, end_line) from code_symbol where job_id = ?",
                "select concat_ws('|', path, imported_name, is_static, is_wildcard, line) from code_import where job_id = ?",
                "select concat_ws('|', path, owner_qualified_name, kind, name, arg_count, receiver_kind, receiver_type, arg_types, line) from code_reference where job_id = ?",
                "select concat_ws('|', path, severity, code, message, line) from parse_diagnostic where job_id = ?",
                "select concat_ws('|', path, handler_qualified_name, http_method, route, framework, reason, line) from code_endpoint where job_id = ?")) {
            assertThat(rows(sql, incrementalJob)).as(sql).isNotEmpty().isEqualTo(rows(sql, fullJob));
        }

        JsonNode endpoints = get("/api/v1/index-jobs/" + incrementalJob + "/endpoints");
        assertThat(endpoints.get("total").asInt()).isEqualTo(1);
        assertThat(endpoints.at("/items/0/route").asText()).isEqualTo("/run");
        assertThat(endpoints.at("/items/0/handlerQualifiedName").asText()).isEqualTo("p.A#run()");

        UUID snapshot = UUID.randomUUID();
        Limits limits = new Limits(100_000, 1_000_000);
        Result fromIncremental = new GraphBuilder().build(snapshot, inputs.load(incrementalJob), limits);
        Result fromFull = new GraphBuilder().build(snapshot, inputs.load(fullJob), limits);
        assertThat(fromIncremental.edges()).isNotEmpty();
        assertThat(sorted(fromIncremental.nodes())).isEqualTo(sorted(fromFull.nodes()));
        assertThat(sorted(fromIncremental.edges())).isEqualTo(sorted(fromFull.edges()));
    }
}
